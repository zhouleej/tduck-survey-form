package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.*;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tduck.cloud.common.util.SecurityUtils;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.*;
import java.util.*;

@Service
@RequiredArgsConstructor
@Slf4j
public class SurveyUpstreamService {
    private final SurveyUpstreamMapper replies;
    private final SurveySyncCursorMapper cursors;
    private final SurveyRecipientMapper recipients;
    private final SurveySuppressionMapper suppressions;
    private final SurveySmsTemplateMapper templates;
    private final SurveyDispatchLeaseMapper leases;
    private final SurveyTemplateService templateService;
    private final SurveyGateway gateway;
    private final SurveyAccess access;
    private final PlatformTransactionManager transactionManager;

    public Page<SurveyUpstream> list(
            String formKey, int page, int size, String keyword, String state) {
        check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        QueryWrapper<SurveyUpstream> query =
                new QueryWrapper<SurveyUpstream>().eq("account_key", gateway.client().accountKey());
        if (formKey == null || formKey.isEmpty()) access.admin();
        else {
            access.requireForm(formKey);
            query.eq("assigned_form_key", formKey);
        }
        if (keyword != null && !keyword.isEmpty())
            query.and(q -> q.like("phone", keyword).or().like("content", keyword));
        if (state != null && !state.isEmpty()) query.eq("state", state);
        return replies.selectPage(
                new Page<>(page, size), query.orderByDesc("recv_time").orderByDesc("id"));
    }

    public List<Map<String, Object>> candidates(Long id) {
        access.admin();
        SurveyUpstream reply = require(id);
        List<Map<String, Object>> values = new ArrayList<>();
        for (SurveyRecipient recipient :
                recipients.selectList(
                        new QueryWrapper<SurveyRecipient>().eq("phone", reply.getPhone()))) {
            try {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("formKey", recipient.getFormKey());
                row.put("formName", access.existingForm(recipient.getFormKey()).getTextName());
                row.put("recipientId", recipient.getId());
                row.put("name", recipient.getName());
                row.put("company", recipient.getCompany());
                values.add(row);
            } catch (Exception ignored) {
            }
        }
        return values;
    }

    @Transactional(rollbackFor = Exception.class)
    public void assign(Long id, String formKey) {
        access.admin();
        SurveyUpstream reply = require(id);
        if (formKey == null || formKey.isEmpty()) {
            reply.setAssignedFormKey(null);
            reply.setRecipientId(null);
        } else {
            access.requireForm(formKey);
            reply.setAssignedFormKey(formKey);
            SurveyRecipient recipient =
                    recipients.selectOne(
                            new QueryWrapper<SurveyRecipient>()
                                    .eq("form_key", formKey)
                                    .eq("phone", reply.getPhone()));
            reply.setRecipientId(recipient == null ? null : recipient.getId());
        }
        replies.update(
                null,
                new UpdateWrapper<SurveyUpstream>()
                        .eq("id", id)
                        .set("assigned_form_key", reply.getAssignedFormKey())
                        .set("recipient_id", reply.getRecipientId())
                        .set("assigned_by", SecurityUtils.getUserId()));
        access.audit(formKey, "REPLY_ASSIGN", id, "管理员明确分配回复归属");
    }

    @Transactional(rollbackFor = Exception.class)
    public void process(Long id, String state, String note) {
        SurveyUpstream reply = require(id);
        if (!SecurityUtils.isAdmin()) {
            check(reply.getAssignedFormKey() != null, "未分配回复仅管理员可查看");
            access.requireForm(reply.getAssignedFormKey());
        }
        check(Arrays.asList("UNHANDLED", "HANDLED").contains(state), "处理状态无效");
        check(note == null || note.length() <= 1000, "备注最多1000字符");
        int changed =
                replies.update(
                        null,
                        new UpdateWrapper<SurveyUpstream>()
                                .eq("id", id)
                                .eq(
                                        !SecurityUtils.isAdmin(),
                                        "assigned_form_key",
                                        reply.getAssignedFormKey())
                                .set("state", state)
                                .set("note", note));
        check(changed == 1, "回复归属已变化，请刷新后重试");
        access.audit(reply.getAssignedFormKey(), "REPLY_PROCESS", id, "更新回复处理状态");
    }

    public Page<SurveySuppression> suppressions(int page, int size, String phone) {
        access.admin();
        check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        return suppressions.selectPage(
                new Page<>(page, size),
                new QueryWrapper<SurveySuppression>()
                        .eq("account_key", gateway.client().accountKey())
                        .like(phone != null && !phone.isEmpty(), "phone", phone)
                        .orderByDesc("id"));
    }

    public List<SurveySyncCursor> health() {
        access.admin();
        return cursors.selectList(
                new QueryWrapper<SurveySyncCursor>()
                        .eq("account_key", gateway.client().accountKey())
                        .select(
                                "id",
                                "kind",
                                "source_key",
                                "window_start",
                                "window_end",
                                "page_num",
                                "failures",
                                "next_poll_at",
                                "last_error")
                        .orderByDesc("id"));
    }

    @Transactional(rollbackFor = Exception.class)
    public void suppress(String phone, String reason, boolean active) {
        access.admin();
        phone = SurveyRecipientService.normalizePhone(phone);
        check(SurveyRecipientService.validPhone(phone), "请输入完整有效号码");
        check(
                reason != null && !reason.trim().isEmpty() && reason.length() <= 500,
                "请填写联系偏好处理依据，最多500字符");
        String account = gateway.client().accountKey();
        SurveySuppression row =
                suppressions.selectOne(
                        new QueryWrapper<SurveySuppression>()
                                .eq("account_key", account)
                                .eq("phone", phone));
        if (row == null) {
            row = new SurveySuppression();
            row.setAccountKey(account);
            row.setPhone(phone);
        }
        row.setActorId(SecurityUtils.getUserId());
        row.setReason(reason);
        row.setActive(active);
        if (row.getId() == null) suppressions.insert(row);
        else suppressions.updateById(row);
        access.audit(null, "SUPPRESSION", row.getId(), active ? "设置账户级本地拒收" : "恢复账户级本地联系");
    }

    public void requestSync() {
        access.admin();
        check(gateway.properties().isYunxinUpstreamEnabled(), "上行接口尚未完成验证或未启用");
        SurveySyncCursor cursor = ensureCursor();
        cursors.update(
                null,
                new UpdateWrapper<SurveySyncCursor>()
                        .eq("id", cursor.getId())
                        .set("next_poll_at", SurveyBatchService.now()));
        access.audit(null, "UPSTREAM_SYNC", cursor.getId(), "安排上行回复同步");
    }

    private SurveySyncCursor ensureCursor() {
        String account = gateway.client().accountKey();
        SurveySyncCursor row =
                cursors.selectOne(
                        new QueryWrapper<SurveySyncCursor>()
                                .eq("account_key", account)
                                .eq("kind", "UPSTREAM")
                                .eq("source_key", "account"));
        if (row != null) return row;
        row = new SurveySyncCursor();
        row.setAccountKey(account);
        row.setKind("UPSTREAM");
        row.setSourceKey("account");
        row.setWindowStart(SurveyBatchService.now().minusHours(24));
        row.setPageNum(1);
        row.setPollStep(0);
        row.setFailures(0);
        row.setManualRequested(false);
        row.setNextPollAt(SurveyBatchService.now());
        try {
            cursors.insert(row);
        } catch (DuplicateKeyException ignored) {
            return cursors.selectOne(
                    new QueryWrapper<SurveySyncCursor>()
                            .eq("account_key", account)
                            .eq("kind", "UPSTREAM")
                            .eq("source_key", "account"));
        }
        return row;
    }

    @Scheduled(initialDelay = 45000, fixedDelay = 60000)
    public void tick() {
        try {
            if (!gateway.properties().isYunxinUpstreamEnabled()) return;
            SurveySyncCursor cursor = ensureCursor();
            if (!cursor.getNextPollAt().isAfter(SurveyBatchService.now())) runDue();
        } catch (Exception e) {
            log.warn("上行同步保留游标等待重试，异常类型:{}", e.getClass().getSimpleName());
        }
    }

    public void runDue() {
        SurveySyncCursor initial = ensureCursor();
        String key = "upstream:" + initial.getAccountKey(), owner = UUID.randomUUID().toString();
        LocalDateTime now = SurveyBatchService.now();
        leases.ensure(key);
        if (leases.claim(key, owner, now, now.plusMinutes(2)) != 1) return;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            SurveySyncCursor cursor = cursors.selectById(initial.getId());
            if (cursor.getWindowEnd() == null) {
                cursor.setWindowEnd(
                        cursor.getWindowStart().plusHours(24).isBefore(now)
                                ? cursor.getWindowStart().plusHours(24)
                                : now);
                if (!cursor.getWindowEnd().isAfter(cursor.getWindowStart())) return;
                cursors.updateById(cursor);
            }
            long deadline = System.nanoTime() + java.util.concurrent.TimeUnit.SECONDS.toNanos(45);
            for (int pages = 0; pages < 15 && System.nanoTime() < deadline; pages++) {
                check(cursor.getPageNum() <= 1000, "上行分页超过限制，请核实协议");
                check(
                        leases.renew(key, owner, SurveyBatchService.now().plusMinutes(2)) == 1,
                        "同步租约失效");
                JSONObject response =
                        gateway.client()
                                .upstream(
                                        cursor.getWindowStart(),
                                        cursor.getWindowEnd(),
                                        cursor.getPageNum(),
                                        100);
                JSONObject data = response.getJSONObject("data");
                check(
                        data != null && data.get("rows") instanceof JSONArray,
                        "上行响应缺少文档约定的data.rows");
                JSONArray rows = data.getJSONArray("rows");
                Long total = data.getLong("total");
                boolean finished =
                        rows.size() < 100
                                || (total != null
                                        && total >= 0
                                        && cursor.getPageNum() * 100L >= total);
                SurveySyncCursor current = cursor;
                tx.execute(
                        status -> {
                            for (Object raw : rows)
                                ingest(current.getAccountKey(), JSONUtil.parseObj(raw));
                            UpdateWrapper<SurveySyncCursor> update =
                                    new UpdateWrapper<SurveySyncCursor>()
                                            .eq("id", current.getId())
                                            .set("failures", 0)
                                            .set("last_error", null);
                            if (finished)
                                update.set("window_start", current.getWindowEnd().minusMinutes(10))
                                        .set("window_end", null)
                                        .set("page_num", 1)
                                        .set(
                                                "next_poll_at",
                                                SurveyBatchService.now()
                                                        .plusMinutes(
                                                                current.getWindowEnd()
                                                                                .isBefore(
                                                                                        SurveyBatchService
                                                                                                .now()
                                                                                                .minusMinutes(
                                                                                                        15))
                                                                        ? 1
                                                                        : 15));
                            else
                                update.set("page_num", current.getPageNum() + 1)
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
            SurveySyncCursor failed = cursors.selectById(initial.getId());
            int failures = Math.min(10, failed.getFailures() + 1);
            cursors.update(
                    null,
                    new UpdateWrapper<SurveySyncCursor>()
                            .eq("id", initial.getId())
                            .set("failures", failures)
                            .set("last_error", "上行查询失败，请核实正式接口权限及返回结构")
                            .set(
                                    "next_poll_at",
                                    SurveyBatchService.now()
                                            .plusMinutes(
                                                    Math.min(60, 1L << Math.min(6, failures)))));
        } finally {
            leases.release(key, owner);
        }
    }

    public void ingest(String account, JSONObject row) {
        String id = row.getStr("id"),
                phone = SurveyRecipientService.normalizePhone(row.getStr("cellphone")),
                content = row.getStr("content"),
                time = row.getStr("recvtime"),
                code = row.getStr("smscode");
        check(
                phone != null
                        && !phone.isEmpty()
                        && phone.length() <= 32
                        && content != null
                        && content.length() <= 10000,
                "上行记录缺少号码或内容");
        String fingerprint =
                DigestUtil.sha256Hex(
                        id != null && !id.isEmpty()
                                ? "id:" + id
                                : "composite:" + phone + "|" + code + "|" + time + "|" + content);
        if (replies.selectCount(
                        new QueryWrapper<SurveyUpstream>()
                                .eq("account_key", account)
                                .eq("fingerprint", fingerprint))
                > 0) return;
        SurveyUpstream reply = new SurveyUpstream();
        reply.setAccountKey(account);
        reply.setProviderRecordId(id);
        reply.setFingerprint(fingerprint);
        reply.setPhone(phone);
        reply.setSmsCode(code);
        reply.setContent(content);
        reply.setRecvTime(SurveyReceiptNormalizer.time(time));
        reply.setState("UNHANDLED");
        replies.insert(reply);
    }

    @Scheduled(initialDelay = 60000, fixedDelay = 900000)
    public void auditTick() {
        try {
            if (!gateway.properties().isYunxinTemplateStatusEnabled()) return;
            String account = gateway.client().accountKey();
            for (SurveySmsTemplate template :
                    templates.selectList(
                            new QueryWrapper<SurveySmsTemplate>()
                                    .eq("account_key", account)
                                    .isNotNull("provider_template_id")
                                    .and(
                                            q ->
                                                    q.isNull("audited_at")
                                                            .or()
                                                            .lt(
                                                                    "audited_at",
                                                                    SurveyBatchService.now()
                                                                            .minusMinutes(15)))
                                    .last("LIMIT 100"))) {
                try {
                    templateService.refreshAuditInternal(template);
                } catch (Exception e) {
                    log.warn("短信模板审核同步失败，模板本地ID:{}", template.getId());
                }
            }
        } catch (Exception e) {
            log.warn("模板审核同步暂不可用");
        }
    }

    private SurveyUpstream require(Long id) {
        SurveyUpstream reply = replies.selectById(id);
        check(
                reply != null && gateway.client().accountKey().equals(reply.getAccountKey()),
                "回复不存在或不属于当前云信账户");
        return reply;
    }
}
