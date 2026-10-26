package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
@Slf4j
public class SurveyReceiptService {
    private final SurveyReceiptMapper receipts;
    private final SurveyDeliveryMapper deliveries;
    private final SurveyBatchMapper batches;
    private final SurveySyncCursorMapper cursors;
    private final SurveyDispatchLeaseMapper leases;
    private final SurveyBatchService batchService;
    private final SurveyGateway gateway;
    private final SurveyAccess access;
    private final PlatformTransactionManager transactionManager;

    public Map<String, Object> enqueueBatch(Long batchId) {
        SurveyBatch batch = batchService.getBatch(batchId);
        check(batch.getAccountKey().equals(gateway.client().accountKey()), "请切换回批次所属云信账户后查询");
        check(gateway.properties().isYunxinReceiptEnabled(), "回执查询尚未完成正式接口验证或未启用");
        List<SurveyDelivery> rows =
                deliveries.selectList(
                        new QueryWrapper<SurveyDelivery>()
                                .eq("batch_id", batchId)
                                .isNotNull("provider_task_id"));
        Set<String> tasks =
                rows.stream()
                        .map(SurveyDelivery::getProviderTaskId)
                        .filter(v -> v != null && !v.isEmpty())
                        .collect(Collectors.toSet());
        check(!tasks.isEmpty(), "当前批次暂无可查询的云信任务号；受理未知须先核实，不能自动重发");
        for (String task : tasks) {
            LocalDateTime first =
                    rows.stream()
                            .filter(
                                    d ->
                                            task.equals(d.getProviderTaskId())
                                                    && d.getSubmittedAt() != null)
                            .map(SurveyDelivery::getSubmittedAt)
                            .min(LocalDateTime::compareTo)
                            .orElse(batch.getCreateTime());
            ensureCursor(batch.getAccountKey(), task, true, first);
        }
        access.audit(batch.getFormKey(), "RECEIPT_REFRESH", batchId, "已安排正式接口回执查询");
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("queued", true);
        result.put("tasks", tasks.size());
        return result;
    }

    public void ensureCursor(String account, String task, boolean force) {
        ensureCursor(account, task, force, null);
    }

    private void ensureCursor(
            String account, String task, boolean force, LocalDateTime requestedFrom) {
        SurveySyncCursor cursor =
                cursors.selectOne(
                        new QueryWrapper<SurveySyncCursor>()
                                .eq("account_key", account)
                                .eq("kind", "RECEIPT")
                                .eq("source_key", task));
        if (cursor == null) {
            cursor = new SurveySyncCursor();
            cursor.setAccountKey(account);
            cursor.setKind("RECEIPT");
            cursor.setSourceKey(task);
            cursor.setPageNum(1);
            cursor.setPollStep(0);
            cursor.setFailures(0);
            cursor.setManualRequested(force);
            cursor.setRequestedFrom(requestedFrom);
            cursor.setNextPollAt(SurveyBatchService.now());
            try {
                cursors.insert(cursor);
            } catch (DuplicateKeyException ignored) {
            }
        } else if (force)
            cursors.update(
                    null,
                    new UpdateWrapper<SurveySyncCursor>()
                            .eq("id", cursor.getId())
                            .set("next_poll_at", SurveyBatchService.now())
                            .set("poll_step", 0)
                            .set("manual_requested", true)
                            .set("requested_from", requestedFrom));
        else if (cursor.getNextPollAt().getYear() >= 2099)
            cursors.update(
                    null,
                    new UpdateWrapper<SurveySyncCursor>()
                            .eq("id", cursor.getId())
                            .set("next_poll_at", SurveyBatchService.now())
                            .set("poll_step", 0));
    }

    @Scheduled(initialDelay = 30000, fixedDelay = 60000)
    public void tick() {
        try {
            if (!gateway.properties().isYunxinReceiptEnabled()) return;
            runDue();
        } catch (Exception e) {
            log.warn("回执同步未完成，保留游标等待重试，异常类型:{}", e.getClass().getSimpleName());
        }
    }

    public void runDue() {
        String account = gateway.client().accountKey();
        LocalDateTime now = SurveyBatchService.now();
        List<SurveyBatch> accountBatches =
                batches.selectList(new QueryWrapper<SurveyBatch>().eq("account_key", account));
        Set<Long> ids = accountBatches.stream().map(SurveyBatch::getId).collect(Collectors.toSet());
        if (ids.isEmpty()) return;
        List<SurveyDelivery> waiting =
                deliveries.selectList(
                        new QueryWrapper<SurveyDelivery>()
                                .in("batch_id", ids)
                                .isNotNull("provider_task_id")
                                .ge("submitted_at", now.minusHours(72))
                                .in("delivery_status", Arrays.asList("WAITING", "UNKNOWN")));
        Set<String> waitingTasks =
                waiting.stream()
                        .map(SurveyDelivery::getProviderTaskId)
                        .filter(v -> v != null && !v.isEmpty())
                        .collect(Collectors.toSet());
        waitingTasks.forEach(task -> ensureCursor(account, task, false));
        deliveries.update(
                null,
                new UpdateWrapper<SurveyDelivery>()
                        .in("batch_id", ids)
                        .lt("submitted_at", now.minusHours(72))
                        .in("delivery_status", Arrays.asList("WAITING", "UNKNOWN"))
                        .set("delivery_status", "TIMEOUT")
                        .set("reason", "超过72小时未确认送达；可在供应商保留期内人工补查"));
        List<SurveySyncCursor> due =
                cursors.selectList(
                        new QueryWrapper<SurveySyncCursor>()
                                .eq("account_key", account)
                                .eq("kind", "RECEIPT")
                                .le("next_poll_at", now)
                                .orderByAsc("next_poll_at")
                                .last("LIMIT 10"));
        for (SurveySyncCursor cursor : due) {
            if (!waitingTasks.contains(cursor.getSourceKey())
                    && !Boolean.TRUE.equals(cursor.getManualRequested())
                    && cursor.getWindowStart() == null) {
                cursors.update(
                        null,
                        new UpdateWrapper<SurveySyncCursor>()
                                .eq("id", cursor.getId())
                                .set("next_poll_at", LocalDateTime.of(2099, 1, 1, 0, 0)));
                continue;
            }
            runCursor(cursor);
        }
    }

    private void runCursor(SurveySyncCursor initial) {
        String
                key =
                        "receipt:"
                                + DigestUtil.sha256Hex(
                                        initial.getAccountKey() + initial.getSourceKey()),
                owner = UUID.randomUUID().toString();
        LocalDateTime now = SurveyBatchService.now();
        leases.ensure(key);
        if (leases.claim(key, owner, now, now.plusMinutes(2)) != 1) return;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            SurveySyncCursor cursor = cursors.selectById(initial.getId());
            if (cursor.getWindowStart() == null || cursor.getWindowEnd() == null) {
                List<Long> batchIds =
                        batches
                                .selectList(
                                        new QueryWrapper<SurveyBatch>()
                                                .eq("account_key", cursor.getAccountKey()))
                                .stream()
                                .map(SurveyBatch::getId)
                                .collect(Collectors.toList());
                if (batchIds.isEmpty()) return;
                SurveyDelivery earliest =
                        deliveries.selectOne(
                                new QueryWrapper<SurveyDelivery>()
                                        .in("batch_id", batchIds)
                                        .eq("provider_task_id", cursor.getSourceKey())
                                        .ge("submitted_at", now.minusHours(72))
                                        .in("delivery_status", Arrays.asList("WAITING", "UNKNOWN"))
                                        .orderByAsc("submitted_at")
                                        .last("LIMIT 1"));
                LocalDateTime start =
                        earliest == null ? cursor.getRequestedFrom() : earliest.getSubmittedAt();
                if (cursor.getRequestedFrom() != null
                        && (start == null || cursor.getRequestedFrom().isBefore(start)))
                    start = cursor.getRequestedFrom();
                if (start == null) return;
                cursor.setWindowStart(start.minusMinutes(10));
                cursor.setWindowEnd(now.plusSeconds(1));
                cursor.setPageNum(1);
                cursor.setPreviousPageHash(null);
                cursors.updateById(cursor);
            }
            long deadline = System.nanoTime() + TimeUnitSeconds(45);
            for (int pages = 0; pages < 15 && System.nanoTime() < deadline; pages++) {
                check(cursor.getPageNum() <= 1000, "查询超过1000页，请核实分页协议并缩小范围");
                check(
                        leases.renew(key, owner, SurveyBatchService.now().plusMinutes(2)) == 1,
                        "查询租约已失效");
                JSONObject response =
                        gateway.client()
                                .receipts(
                                        cursor.getSourceKey(),
                                        cursor.getWindowStart(),
                                        cursor.getWindowEnd(),
                                        cursor.getPageNum(),
                                        100);
                Object data = response.get("data");
                check(data instanceof JSONArray, "任务查询响应data不是文档约定的列表，已保留游标");
                JSONArray rows = (JSONArray) data;
                for (Object value : rows)
                    check(value instanceof JSONObject || value instanceof Map, "回执记录格式无效");
                String pageHash =
                        DigestUtil.sha256Hex(
                                rows.stream()
                                        .map(
                                                value ->
                                                        SurveyReceiptNormalizer.identity(
                                                                SurveyReceiptNormalizer.safe(
                                                                        JSONUtil.parseObj(value),
                                                                        gateway.properties())))
                                        .sorted()
                                        .collect(Collectors.joining("|")));
                boolean repeated =
                        cursor.getPageNum() > 1 && pageHash.equals(cursor.getPreviousPageHash());
                boolean verifiedClamp =
                        "JX_20261026".equals(gateway.properties().getYunxinReceiptProfile());
                check(!repeated || verifiedClamp, "查询重复同一页，分页协议尚未验证，已保留游标");
                // 实测网关会把越界页钳制到最后一页，不能只等空页来判断结束。
                boolean finished = rows.size() < 100 || repeated;
                final SurveySyncCursor current = cursor;
                tx.execute(
                        status -> {
                            for (Object value : rows)
                                ingest(current.getAccountKey(), JSONUtil.parseObj(value));
                            UpdateWrapper<SurveySyncCursor> update =
                                    new UpdateWrapper<SurveySyncCursor>()
                                            .eq("id", current.getId())
                                            .set("failures", 0)
                                            .set("last_error", null);
                            if (finished) {
                                int step = Math.min(4, current.getPollStep() + 1);
                                long delay =
                                        new long[] {1, 5, 15, 60, 60}
                                                [Math.min(current.getPollStep(), 4)];
                                update.set("window_start", null)
                                        .set("window_end", null)
                                        .set("page_num", 1)
                                        .set("poll_step", step)
                                        .set("manual_requested", false)
                                        .set("requested_from", null)
                                        .set("previous_page_hash", null)
                                        .set(
                                                "next_poll_at",
                                                SurveyBatchService.now().plusMinutes(delay));
                            } else
                                update.set("page_num", current.getPageNum() + 1)
                                        .set("previous_page_hash", pageHash)
                                        .set(
                                                "next_poll_at",
                                                SurveyBatchService.now().plusMinutes(1));
                            cursors.update(null, update);
                            return null;
                        });
                cursor = cursors.selectById(cursor.getId());
                if (finished) break;
            }
        } catch (Exception e) {
            int failures =
                    Math.min(
                            10,
                            Optional.ofNullable(cursors.selectById(initial.getId()).getFailures())
                                            .orElse(0)
                                    + 1);
            cursors.update(
                    null,
                    new UpdateWrapper<SurveySyncCursor>()
                            .eq("id", initial.getId())
                            .set("failures", failures)
                            .set("last_error", "正式回执查询失败，请检查已验证的参数、权限及返回结构")
                            .set(
                                    "next_poll_at",
                                    SurveyBatchService.now()
                                            .plusMinutes(
                                                    Math.min(60, 1L << Math.min(6, failures)))));
        } finally {
            leases.release(key, owner);
        }
    }

    private static long TimeUnitSeconds(int value) {
        return java.util.concurrent.TimeUnit.SECONDS.toNanos(value);
    }

    /** 在每页处理事务内调用。重复事件不重复改变明细或计数。 */
    public void ingest(String account, JSONObject input) {
        JSONObject row = SurveyReceiptNormalizer.safe(input, gateway.properties());
        String fingerprint = SurveyReceiptNormalizer.fingerprint(row);
        if (receipts.selectCount(
                        new QueryWrapper<SurveyReceipt>()
                                .eq("account_key", account)
                                .eq("fingerprint", fingerprint))
                > 0) return;
        SurveyReceipt receipt = new SurveyReceipt();
        receipt.setAccountKey(account);
        receipt.setFingerprint(fingerprint);
        receipt.setRecordIdentity(SurveyReceiptNormalizer.identity(row));
        receipt.setProviderRecordId(row.getStr("id"));
        receipt.setProviderTaskId(row.getStr("taskid"));
        receipt.setPhone(row.getStr("phoneno"));
        receipt.setProviderMsgId(row.getStr("msgid"));
        receipt.setCustId(row.getStr("custid"));
        receipt.setSendTime(SurveyReceiptNormalizer.time(row.getStr("sendtime")));
        receipt.setRecvTime(SurveyReceiptNormalizer.time(row.getStr("recvtime")));
        receipt.setRawJson(row.toString());
        receipt.setMatchState("UNMATCHED");
        receipt.setMatchReason("没有可唯一关联的本地发送记录");
        if (!SurveyRecipientService.validPhone(receipt.getPhone())
                || receipt.getProviderTaskId() == null) {
            receipt.setMatchState("UNUSABLE");
            receipt.setMatchReason("缺少完整号码或任务号，无法自动关联");
            receipts.insert(receipt);
            return;
        }
        List<Long> batchIds =
                batches
                        .selectList(new QueryWrapper<SurveyBatch>().eq("account_key", account))
                        .stream()
                        .map(SurveyBatch::getId)
                        .collect(Collectors.toList());
        List<SurveyDelivery> candidates =
                batchIds.isEmpty()
                        ? Collections.emptyList()
                        : deliveries.selectList(
                                new QueryWrapper<SurveyDelivery>()
                                        .in("batch_id", batchIds)
                                        .eq("provider_task_id", receipt.getProviderTaskId())
                                        .eq("phone", receipt.getPhone()));
        Set<Long> previous =
                receipts
                        .selectList(
                                new QueryWrapper<SurveyReceipt>()
                                        .eq("account_key", account)
                                        .eq("record_identity", receipt.getRecordIdentity())
                                        .isNotNull("matched_delivery_id")
                                        .likeRight("match_state", "MATCHED"))
                        .stream()
                        .map(SurveyReceipt::getMatchedDeliveryId)
                        .collect(Collectors.toSet());
        if (previous.size() == 1) {
            Long id = previous.iterator().next();
            candidates =
                    candidates.stream()
                            .filter(d -> id.equals(d.getId()))
                            .collect(Collectors.toList());
            receipt.setMatchReason("已确认的外部记录标识");
        } else {
            if (receipt.getProviderMsgId() != null && !receipt.getProviderMsgId().isEmpty()) {
                List<SurveyDelivery> exact =
                        candidates.stream()
                                .filter(
                                        d ->
                                                receipt.getProviderMsgId()
                                                        .equals(d.getProviderMsgId()))
                                .collect(Collectors.toList());
                if (!exact.isEmpty()) candidates = exact;
            }
            if (receipt.getSendTime() != null)
                candidates =
                        candidates.stream()
                                .filter(
                                        d ->
                                                d.getSubmittedAt() != null
                                                        && !d.getSubmittedAt()
                                                                .isBefore(
                                                                        receipt.getSendTime()
                                                                                .minusMinutes(10))
                                                        && !d.getSubmittedAt()
                                                                .isAfter(
                                                                        receipt.getSendTime()
                                                                                .plusMinutes(10)))
                                .collect(Collectors.toList());
            else candidates = Collections.emptyList();
            receipt.setMatchReason("任务、完整号码、消息标识及发送时间窗的唯一候选");
        }
        if (candidates.size() == 1) {
            SurveyDelivery delivery =
                    deliveries.selectOne(
                            new QueryWrapper<SurveyDelivery>()
                                    .eq("id", candidates.get(0).getId())
                                    .last("FOR UPDATE"));
            if (delivery.getProviderRecordId() != null
                    && !delivery.getProviderRecordId().equals(receipt.getRecordIdentity())) {
                receipt.setMatchState("AMBIGUOUS");
                receipt.setMatchReason("多个外部发送记录对应同一本地明细，需要人工核对");
                receipts.update(
                        null,
                        new UpdateWrapper<SurveyReceipt>()
                                .eq("matched_delivery_id", delivery.getId())
                                .set("match_state", "AMBIGUOUS")
                                .set("match_reason", receipt.getMatchReason()));
                deliveries.update(
                        null,
                        new UpdateWrapper<SurveyDelivery>()
                                .eq("id", delivery.getId())
                                .set("send_status", "UNKNOWN")
                                .set("delivery_status", "UNKNOWN")
                                .set("split_size", null)
                                .set("reason", receipt.getMatchReason()));
            } else {
                receipt.setMatchedDeliveryId(delivery.getId());
                receipt.setMatchState("MATCHED");
                apply(delivery, receipt, row);
            }
        } else if (candidates.size() > 1) {
            receipt.setMatchState("AMBIGUOUS");
            receipt.setMatchReason("同一时间窗内存在多次本地发送，不能自动归属");
        }
        receipts.insert(receipt);
    }

    private void apply(SurveyDelivery delivery, SurveyReceipt receipt, JSONObject row) {
        String send = SurveyReceiptNormalizer.sendStatus(row, gateway.properties()),
                recv = SurveyReceiptNormalizer.deliveryStatus(row, gateway.properties());
        // 迟到的中间状态不能冲掉已确认成功；明确失败可被后续成功修正。
        if ("SUCCESS".equals(delivery.getSendStatus()) && !"SUCCESS".equals(send))
            send = delivery.getSendStatus();
        if ("SUCCESS".equals(delivery.getDeliveryStatus()) && !"SUCCESS".equals(recv))
            recv = delivery.getDeliveryStatus();
        String reason =
                "发送:"
                        + Objects.toString(row.getStr("sendstatus"), "")
                        + " "
                        + Objects.toString(row.getStr("sendstatustext"), "")
                        + "；接收:"
                        + Objects.toString(row.getStr("recvstatus"), "")
                        + " "
                        + Objects.toString(row.getStr("recvstatustext"), "");
        if (reason.length() > 450) reason = reason.substring(0, 450);
        deliveries.update(
                null,
                new UpdateWrapper<SurveyDelivery>()
                        .eq("id", delivery.getId())
                        .set("send_status", send)
                        .set("delivery_status", recv)
                        .set("provider_record_id", receipt.getRecordIdentity())
                        .set("send_time", receipt.getSendTime())
                        .set("recv_time", receipt.getRecvTime())
                        .set("synced_at", SurveyBatchService.now())
                        .set("reason", reason));
        String split = row.getStr("splitSize");
        if (split != null && split.matches("[0-9]{1,7}")) {
            deliveries.update(
                    null,
                    new UpdateWrapper<SurveyDelivery>()
                            .eq("id", delivery.getId())
                            .set("split_size", Integer.parseInt(split)));
        }
    }

    public Page<SurveyReceipt> unmatched(int page, int size) {
        access.admin();
        check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        return receipts.selectPage(
                new Page<>(page, size),
                new QueryWrapper<SurveyReceipt>()
                        .eq("account_key", gateway.client().accountKey())
                        .notLikeRight("match_state", "MATCHED")
                        .orderByDesc("id"));
    }

    @org.springframework.transaction.annotation.Transactional(rollbackFor = Exception.class)
    public void assign(Long receiptId, Long deliveryId, String note) {
        access.admin();
        check(note != null && !note.trim().isEmpty() && note.length() <= 500, "请填写人工核对依据（最多500字符）");
        SurveyReceipt receipt =
                receipts.selectOne(
                        new QueryWrapper<SurveyReceipt>().eq("id", receiptId).last("FOR UPDATE"));
        check(
                receipt != null && receipt.getAccountKey().equals(gateway.client().accountKey()),
                "回执不存在或不属于当前账户");
        SurveyDelivery delivery =
                deliveries.selectOne(
                        new QueryWrapper<SurveyDelivery>().eq("id", deliveryId).last("FOR UPDATE"));
        check(delivery != null, "邀请明细不存在");
        SurveyBatch batch = batchService.getBatch(delivery.getBatchId());
        check(
                batch.getAccountKey().equals(receipt.getAccountKey())
                        && Objects.equals(delivery.getPhone(), receipt.getPhone())
                        && Objects.equals(
                                delivery.getProviderTaskId(), receipt.getProviderTaskId()),
                "人工关联也必须核对账户、完整号码和任务号");
        receipt.setMatchedDeliveryId(deliveryId);
        receipt.setMatchState("MATCHED_MANUAL");
        receipt.setMatchReason(note);
        receipts.updateById(receipt);
        apply(delivery, receipt, JSONUtil.parseObj(receipt.getRawJson()));
        access.audit(
                batch.getFormKey(),
                "RECEIPT_ASSIGN",
                receiptId,
                "人工关联明细" + deliveryId + "：" + note);
    }
}
