package com.tduck.cloud.form.survey;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tduck.cloud.common.sms.*;
import com.tduck.cloud.form.entity.enums.FormStatusEnum;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.LocalDateTime;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Collectors;

import javax.annotation.PreDestroy;

/** 持久化发件箱。认领先提交，网络调用在事务外执行；恢复只转未知，不重复发送。 */
@Component
@RequiredArgsConstructor
@Slf4j
public class SurveyDispatchWorker {
    private final SurveyBatchMapper batches;
    private final SurveyDeliveryMapper deliveries;
    private final SurveySendAttemptMapper attempts;
    private final SurveyRecipientMapper recipients;
    private final SurveySmsTemplateMapper templates;
    private final SurveyDispatchLeaseMapper leases;
    private final SurveyBatchService service;
    private final SurveyGateway gateway;
    private final SurveyAccess access;
    private final PlatformTransactionManager transactionManager;
    private final AtomicBoolean running = new AtomicBoolean();
    private final ExecutorService executor =
            new ThreadPoolExecutor(
                    1,
                    1,
                    0,
                    TimeUnit.MILLISECONDS,
                    new SynchronousQueue<>(),
                    r -> {
                        Thread t = new Thread(r, "survey-sms-dispatch");
                        t.setDaemon(true);
                        return t;
                    },
                    new ThreadPoolExecutor.AbortPolicy());

    @Scheduled(initialDelay = 15000, fixedDelay = 60000)
    public void tick() {
        try {
            if (!gateway.properties().isSurveyEnabled() || !running.compareAndSet(false, true))
                return;
        } catch (Exception e) {
            return;
        }
        try {
            executor.execute(
                    () -> {
                        try {
                            runOnce();
                        } catch (Exception e) {
                            log.warn("调研短信队列执行失败，等待下次扫描，异常类型:{}", e.getClass().getSimpleName());
                        } finally {
                            running.set(false);
                        }
                    });
        } catch (RejectedExecutionException e) {
            running.set(false);
        }
    }

    @PreDestroy
    public void close() {
        executor.shutdown();
    }

    public void runOnce() {
        SmsPlatformProperties properties = gateway.properties();
        if (!properties.isSurveyEnabled()) return;
        YunxinClient client = new YunxinClient(properties);
        String key = "send:" + client.accountKey(), owner = UUID.randomUUID().toString();
        LocalDateTime now = SurveyBatchService.now();
        leases.ensure(key);
        if (leases.claim(key, owner, now, now.plusMinutes(3)) != 1) return;
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        try {
            tx.execute(
                    status -> {
                        recover(client.accountKey());
                        return null;
                    });
            for (int i = 0; i < 100 && !Thread.currentThread().isInterrupted(); i++) {
                LocalDateTime current = SurveyBatchService.now();
                if (!gateway.properties().isSurveyEnabled()) break;
                if (current.getHour() < properties.getSurveySendStartHour()
                        || current.getHour() >= properties.getSurveySendEndHour()) break;
                if (leases.renew(key, owner, current.plusMinutes(3)) != 1) break;
                LocalDateTime next = leases.getNextRequestAt(key);
                if (next != null && next.isAfter(current)) {
                    long millis = java.time.Duration.between(current, next).toMillis();
                    try {
                        Thread.sleep(Math.min(millis, 60000));
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        break;
                    }
                }
                Prepared prepared = tx.execute(status -> prepare(client));
                if (prepared == null) break;
                if (prepared.attempt == null) continue;
                leases.setNextRequestAt(
                        key,
                        owner,
                        SurveyBatchService.now()
                                .plusSeconds(
                                        Math.max(
                                                1,
                                                Math.min(
                                                        60,
                                                        properties
                                                                .getSurveySendIntervalSeconds()))));
                String state = "ACCEPTED",
                        reason = "云信已受理，等待发送及送达回执",
                        code = "0000000",
                        task = null,
                        msg = null;
                try {
                    JSONObject result =
                            client.send(
                                    prepared.template.getProviderTemplateId(),
                                    prepared.payload,
                                    Boolean.TRUE.equals(prepared.template.getVariable()));
                    task = result.getStr("taskid");
                    msg = result.getStr("msgid");
                    if (task == null || task.trim().isEmpty()) {
                        state = "UNKNOWN";
                        reason = "云信返回成功但缺少任务ID，请核实受理结果";
                    }
                } catch (YunxinGatewayException e) {
                    state = e.isUncertain() ? "UNKNOWN" : "REJECTED";
                    reason = e.getMessage();
                    code = e.getProviderCode();
                } catch (Exception e) {
                    state = "UNKNOWN";
                    reason = "发送结果未知，请核实后再处理";
                    code = null;
                }
                final String outcome = state,
                        description = reason,
                        providerCode = code,
                        taskId = task,
                        msgId = msg;
                tx.execute(
                        status -> {
                            finish(
                                    prepared.attempt,
                                    outcome,
                                    description,
                                    providerCode,
                                    taskId,
                                    msgId);
                            return null;
                        });
            }
        } finally {
            leases.release(key, owner);
        }
    }

    private Prepared prepare(YunxinClient client) {
        SurveyBatch batch =
                batches.selectOne(
                        new QueryWrapper<SurveyBatch>()
                                .eq("account_key", client.accountKey())
                                .in("status", Arrays.asList("PENDING", "RUNNING"))
                                .le("scheduled_at", SurveyBatchService.now())
                                .orderByAsc("scheduled_at")
                                .orderByAsc("id")
                                .last("LIMIT 1 FOR UPDATE"));
        if (batch == null) return null;
        try {
            if (access.existingForm(batch.getFormKey()).getStatus() != FormStatusEnum.RELEASE)
                return pause(batch, "问卷已停止收集，需重新发布后人工继续");
            if (!batch.getConfigVersion().equals(gateway.client().configurationVersion()))
                return pause(batch, "短信配置已变化，请核对后人工继续");
            SurveySmsTemplate template = templates.selectById(batch.getTemplateId());
            if (template == null
                    || !batch.getTemplateVersion().equals(template.getVersion())
                    || !SurveyTemplateService.approved(template))
                return pause(batch, "模板已变化或审核不可用，请取消待发送部分并重新预览");
            if (!SurveyTemplateService.allCarriersApproved(template)
                    && !Boolean.TRUE.equals(batch.getCoverageAccepted()))
                return pause(batch, "请重新核对运营商审核覆盖范围");
            List<SurveyDelivery> pending =
                    deliveries.selectList(
                            new QueryWrapper<SurveyDelivery>()
                                    .eq("batch_id", batch.getId())
                                    .eq("submit_status", "PENDING")
                                    .orderByAsc("id")
                                    .last("LIMIT 100"));
            if (pending.isEmpty()) {
                complete(batch.getId());
                return new Prepared();
            }
            List<SurveyRecipient> current =
                    recipients.selectBatchIds(
                            pending.stream()
                                    .map(SurveyDelivery::getRecipientId)
                                    .collect(Collectors.toList()));
            Map<Long, String> excluded =
                    service.exclusionReasons(
                            batch.getFormKey(),
                            current,
                            batch.getKind(),
                            batch.getId(),
                            batch.getAccountKey());
            Set<Long> present =
                    current.stream().map(SurveyRecipient::getId).collect(Collectors.toSet());
            List<SurveyDelivery> selected = new ArrayList<>();
            for (SurveyDelivery row : pending) {
                String reason =
                        present.contains(row.getRecipientId())
                                ? excluded.get(row.getRecipientId())
                                : "联系人不存在";
                if (reason != null)
                    deliveries.update(
                            null,
                            new UpdateWrapper<SurveyDelivery>()
                                    .eq("id", row.getId())
                                    .eq("submit_status", "PENDING")
                                    .set("submit_status", "SKIPPED")
                                    .set("reason", reason));
                else selected.add(row);
            }
            if (selected.isEmpty()) {
                complete(batch.getId());
                return new Prepared();
            }
            List<Map<String, Object>> payload = new ArrayList<>();
            for (SurveyDelivery row : selected) {
                Map<String, Object> values =
                        new LinkedHashMap<>(JSONUtil.parseObj(row.getVariablesJson()));
                values.put("phone", row.getPhone());
                payload.add(values);
            }
            SurveySendAttempt attempt = new SurveySendAttempt();
            attempt.setFormKey(batch.getFormKey());
            attempt.setBatchId(batch.getId());
            attempt.setAccountKey(batch.getAccountKey());
            attempt.setState("SUBMITTING");
            attempt.setStartedAt(SurveyBatchService.now());
            attempt.setRequestDigest(DigestUtil.sha256Hex(JSONUtil.toJsonStr(payload)));
            attempts.insert(attempt);
            deliveries.update(
                    null,
                    new UpdateWrapper<SurveyDelivery>()
                            .in(
                                    "id",
                                    selected.stream()
                                            .map(SurveyDelivery::getId)
                                            .collect(Collectors.toList()))
                            .eq("submit_status", "PENDING")
                            .set("attempt_id", attempt.getId())
                            .set("submit_status", "SUBMITTING")
                            .set("submitted_at", attempt.getStartedAt()));
            batch.setStatus("RUNNING");
            batch.setReason("");
            batches.updateById(batch);
            Prepared result = new Prepared();
            result.attempt = attempt;
            result.template = JSONUtil.toBean(batch.getTemplateSnapshot(), SurveySmsTemplate.class);
            result.payload = payload;
            return result;
        } catch (com.tduck.cloud.common.exception.BaseException e) {
            return pause(batch, "问卷或发送条件不可用，请核对后人工继续");
        }
    }

    private Prepared pause(SurveyBatch batch, String reason) {
        batch.setStatus("PAUSED");
        batch.setReason(reason);
        batches.updateById(batch);
        access.audit(batch.getFormKey(), "BATCH_AUTO_PAUSE", batch.getId(), reason);
        return new Prepared();
    }

    private void finish(
            SurveySendAttempt attempt,
            String state,
            String reason,
            String code,
            String task,
            String msg) {
        attempts.update(
                null,
                new UpdateWrapper<SurveySendAttempt>()
                        .eq("id", attempt.getId())
                        .in("state", Arrays.asList("SUBMITTING", "UNKNOWN"))
                        .set("state", state)
                        .set("reason", reason)
                        .set("provider_code", code)
                        .set("provider_task_id", task)
                        .set("provider_msg_id", msg)
                        .set("finished_at", SurveyBatchService.now()));
        deliveries.update(
                null,
                new UpdateWrapper<SurveyDelivery>()
                        .eq("attempt_id", attempt.getId())
                        .set("submit_status", state)
                        .set(
                                "send_status",
                                "REJECTED".equals(state)
                                        ? "FAILED"
                                        : "UNKNOWN".equals(state) ? "UNKNOWN" : "PENDING")
                        .set("delivery_status", "REJECTED".equals(state) ? "NOT_SENT" : "WAITING")
                        .set("reason", reason)
                        .set("provider_task_id", task)
                        .set("provider_msg_id", msg));
        complete(attempt.getBatchId());
    }

    private void recover(String account) {
        List<SurveySendAttempt> abandoned =
                attempts.selectList(
                        new QueryWrapper<SurveySendAttempt>()
                                .eq("account_key", account)
                                .eq("state", "SUBMITTING")
                                .lt("started_at", SurveyBatchService.now().minusMinutes(3)));
        for (SurveySendAttempt attempt : abandoned)
            finish(attempt, "UNKNOWN", "发送进程中断或租约过期，受理结果未知，禁止自动重发", null, null, null);
    }

    private void complete(Long batchId) {
        long pending =
                deliveries.selectCount(
                        new QueryWrapper<SurveyDelivery>()
                                .eq("batch_id", batchId)
                                .in("submit_status", Arrays.asList("PENDING", "SUBMITTING")));
        if (pending == 0)
            batches.update(
                    null,
                    new UpdateWrapper<SurveyBatch>()
                            .eq("id", batchId)
                            .in("status", Arrays.asList("PENDING", "RUNNING"))
                            .set("status", "COMPLETED")
                            .set("reason", "发送执行已结束；送达结果以回执为准"));
    }

    private static class Prepared {
        SurveySendAttempt attempt;
        SurveySmsTemplate template;
        List<Map<String, Object>> payload;
    }
}
