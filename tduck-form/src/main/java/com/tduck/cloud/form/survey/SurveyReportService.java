package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.io.IOException;
import java.util.*;
import java.util.stream.Collectors;

import javax.servlet.http.HttpServletResponse;

@Service
@RequiredArgsConstructor
public class SurveyReportService {
    private final SurveyRecipientMapper recipients;
    private final SurveyDeliveryMapper deliveries;
    private final SurveyBatchMapper batches;
    private final SurveyInvitationMapper invitations;
    private final SurveySendAttemptMapper attempts;
    private final SurveyProgressMapper progress;
    private final SurveyUpstreamService upstream;
    private final SurveySettingsService settings;
    private final SurveyBatchService batchService;
    private final SurveyAccess access;

    public Map<String, Object> report(
            String formKey, Long batchId, String groupName, String company) {
        access.requireForm(formKey);
        String mode = settings.get(formKey).getMode();
        QueryWrapper<SurveyRecipient> contacts =
                new QueryWrapper<SurveyRecipient>().eq("form_key", formKey).eq("disabled", false);
        filter(contacts, groupName, company);
        List<SurveyDelivery> rows =
                deliveries.selectList(
                        deliveryFilter(formKey, batchId, groupName, company)
                                .select(
                                        "id",
                                        "batch_id",
                                        "recipient_id",
                                        "attempt_id",
                                        "submit_status",
                                        "send_status",
                                        "delivery_status",
                                        "split_size"));
        Set<Long> planned =
                rows.stream().map(SurveyDelivery::getRecipientId).collect(Collectors.toSet());
        Set<Long> invited =
                rows.stream()
                        .filter(r -> r.getAttemptId() != null)
                        .map(SurveyDelivery::getRecipientId)
                        .collect(Collectors.toSet());
        Set<Long> completed = new HashSet<>(progress.completed(formKey)),
                visited = new HashSet<>(progress.visited(formKey));
        Set<Long> cohortCompleted = new HashSet<>(completed);
        cohortCompleted.retainAll(invited);
        Set<Long> cohortVisited = new HashSet<>(visited);
        cohortVisited.retainAll(invited);
        List<SurveyAnswerSource> answerSources = progress.sources(formKey);
        Set<Long> batchIds =
                rows.stream().map(SurveyDelivery::getBatchId).collect(Collectors.toSet());
        long answerCount =
                answerSources.stream()
                        .filter(s -> batchId == null || batchId.equals(s.getBatchId()))
                        .filter(
                                s ->
                                        "ANONYMOUS".equals(mode)
                                                || planned.contains(s.getRecipientId()))
                        .count();
        boolean anonymousUnattributable =
                "ANONYMOUS".equals(mode)
                        && ((groupName != null && !groupName.isEmpty())
                                || (company != null && !company.isEmpty()));
        QueryWrapper<SurveyInvitation> inviteQuery =
                new QueryWrapper<SurveyInvitation>().eq("form_key", formKey);
        if (batchId != null) inviteQuery.eq("batch_id", batchId);
        if ("TRACKED".equals(mode)) {
            if (planned.isEmpty()) inviteQuery.apply("1=0");
            else inviteQuery.in("recipient_id", planned);
        }
        long visits =
                invitations.selectList(inviteQuery.select("visit_count")).stream()
                        .mapToLong(v -> v.getVisitCount() == null ? 0 : v.getVisitCount())
                        .sum();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("mode", mode);
        result.put("validRecipients", recipients.selectCount(contacts));
        result.put("plannedRecipients", planned.size());
        result.put("invitedRecipients", invited.size());
        result.put(
                "invitationAttempts", rows.stream().filter(r -> r.getAttemptId() != null).count());
        result.put(
                "networkRequests",
                rows.stream()
                        .map(SurveyDelivery::getAttemptId)
                        .filter(Objects::nonNull)
                        .distinct()
                        .count());
        result.put("accepted", count(rows, "submit", "ACCEPTED"));
        result.put("sendSuccess", count(rows, "send", "SUCCESS"));
        result.put("deliverySuccess", count(rows, "delivery", "SUCCESS"));
        result.put(
                "failed",
                rows.stream()
                        .filter(
                                r ->
                                        "REJECTED".equals(r.getSubmitStatus())
                                                || "FAILED".equals(r.getSendStatus())
                                                || "FAILED".equals(r.getDeliveryStatus()))
                        .count());
        result.put(
                "unknown",
                rows.stream()
                        .filter(
                                r ->
                                        "UNKNOWN".equals(r.getSubmitStatus())
                                                || "UNKNOWN".equals(r.getSendStatus())
                                                || Arrays.asList("UNKNOWN", "TIMEOUT")
                                                        .contains(r.getDeliveryStatus()))
                        .count());
        result.put("pending", count(rows, "submit", "PENDING"));
        result.put("visitCount", anonymousUnattributable ? null : visits);
        result.put("attributedAnswers", anonymousUnattributable ? null : answerCount);
        result.put("visitedRecipients", "TRACKED".equals(mode) ? cohortVisited.size() : null);
        result.put("completedRecipients", "TRACKED".equals(mode) ? cohortCompleted.size() : null);
        result.put(
                "incompleteRecipients",
                "TRACKED".equals(mode) ? invited.size() - cohortCompleted.size() : null);
        result.put(
                "completionRate",
                "TRACKED".equals(mode) && !invited.isEmpty()
                        ? Math.round(cohortCompleted.size() * 10000.0 / invited.size()) / 100.0
                        : null);
        long segmentsKnown = rows.stream().filter(r -> r.getSplitSize() != null).count();
        result.put(
                "actualSegments",
                segmentsKnown == 0
                        ? null
                        : rows.stream()
                                .filter(r -> r.getSplitSize() != null)
                                .mapToLong(SurveyDelivery::getSplitSize)
                                .sum());
        result.put("segmentsKnownRecords", segmentsKnown);
        result.put("segmentsNote", "仅汇总正式接口实际返回的拆分条数，已知" + segmentsKnown + "条明细；其余未提供，不以人数推算费用");
        result.put("anonymousGroupAttributionUnavailable", anonymousUnattributable);
        return result;
    }

    public void exportDeliveries(
            Long batchId,
            String keyword,
            String submitStatus,
            String sendStatus,
            String deliveryStatus,
            String participation,
            HttpServletResponse response)
            throws IOException {
        SurveyBatch batch = batchService.getBatch(batchId);
        QueryWrapper<SurveyDelivery> query =
                new QueryWrapper<SurveyDelivery>().eq("batch_id", batchId);
        if (keyword != null && !keyword.isEmpty())
            query.and(
                    q ->
                            q.like("phone", keyword)
                                    .or()
                                    .like("name", keyword)
                                    .or()
                                    .like("company", keyword));
        query.eq(submitStatus != null && !submitStatus.isEmpty(), "submit_status", submitStatus)
                .eq(sendStatus != null && !sendStatus.isEmpty(), "send_status", sendStatus)
                .eq(
                        deliveryStatus != null && !deliveryStatus.isEmpty(),
                        "delivery_status",
                        deliveryStatus);
        Set<Long> completed = new HashSet<>(progress.completed(batch.getFormKey())),
                visited = new HashSet<>(progress.visited(batch.getFormKey()));
        SurveyBatchService.applyParticipation(
                query, batch.getMode(), participation, completed, visited, "recipient_id");
        List<Map<String, Object>> rows = new ArrayList<>();
        for (SurveyDelivery row : deliveries.selectList(query.orderByAsc("id"))) {
            Map<String, Object> value = new LinkedHashMap<>();
            value.put("本地批次", batchId);
            value.put("手机号", row.getPhone());
            value.put("姓名", SurveyRecipientService.safeExcel(row.getName()));
            value.put("单位", SurveyRecipientService.safeExcel(row.getCompany()));
            value.put("分组", SurveyRecipientService.safeExcel(row.getGroupName()));
            value.put("提交状态", row.getSubmitStatus());
            value.put("发送状态", row.getSendStatus());
            value.put("送达状态", row.getDeliveryStatus());
            value.put(
                    "填写状态",
                    "TRACKED".equals(batch.getMode())
                            ? completed.contains(row.getRecipientId())
                                    ? "已完成"
                                    : visited.contains(row.getRecipientId()) ? "已访问未完成" : "未访问"
                            : "匿名不关联");
            value.put("说明", SurveyRecipientService.safeExcel(row.getReason()));
            value.put("云信任务", row.getProviderTaskId());
            value.put("提交时间", row.getSubmittedAt() == null ? "" : row.getSubmittedAt().toString());
            rows.add(value);
        }
        access.audit(batch.getFormKey(), "DELIVERY_EXPORT", batchId, "导出筛选明细" + rows.size() + "条");
        SurveyRecipientService.writeExcel(
                response, rows, Arrays.asList("本地批次", "手机号", "提交状态", "发送状态", "送达状态"));
    }

    public void exportIncomplete(
            String formKey,
            Long batchId,
            String groupName,
            String company,
            HttpServletResponse response)
            throws IOException {
        access.requireForm(formKey);
        check("TRACKED".equals(settings.get(formKey).getMode()), "匿名调研无法导出逐人未完成名单");
        Set<Long> completed = new HashSet<>(progress.completed(formKey));
        Set<Long> invited =
                deliveries
                        .selectList(
                                deliveryFilter(formKey, batchId, groupName, company)
                                        .isNotNull("attempt_id")
                                        .select("recipient_id"))
                        .stream()
                        .map(SurveyDelivery::getRecipientId)
                        .collect(Collectors.toSet());
        invited.removeAll(completed);
        List<Map<String, Object>> rows = new ArrayList<>();
        if (!invited.isEmpty())
            for (SurveyRecipient recipient :
                    recipients.selectList(
                            new QueryWrapper<SurveyRecipient>()
                                    .eq("form_key", formKey)
                                    .in("id", invited))) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("手机号", recipient.getPhone());
                row.put("姓名", SurveyRecipientService.safeExcel(recipient.getName()));
                row.put("单位", SurveyRecipientService.safeExcel(recipient.getCompany()));
                row.put("分组", SurveyRecipientService.safeExcel(recipient.getGroupName()));
                row.put("备注", SurveyRecipientService.safeExcel(recipient.getRemark()));
                rows.add(row);
            }
        access.audit(formKey, "INCOMPLETE_EXPORT", batchId, "导出未完成对象" + rows.size() + "条");
        SurveyRecipientService.writeExcel(
                response, rows, Arrays.asList("手机号", "姓名", "单位", "分组", "备注"));
    }

    public void exportReplies(String formKey, HttpServletResponse response) throws IOException {
        List<Map<String, Object>> rows = new ArrayList<>();
        for (int page = 1; page <= 1000; page++) {
            com.baomidou.mybatisplus.extension.plugins.pagination.Page<SurveyUpstream> result =
                    upstream.list(formKey, page, 100, null, null);
            for (SurveyUpstream reply : result.getRecords()) {
                Map<String, Object> row = new LinkedHashMap<>();
                row.put("手机号", SurveyRecipientService.safeExcel(reply.getPhone()));
                row.put("回复内容", SurveyRecipientService.safeExcel(reply.getContent()));
                row.put("回复时间", reply.getRecvTime() == null ? "" : reply.getRecvTime().toString());
                row.put("处理状态", reply.getState());
                row.put("备注", SurveyRecipientService.safeExcel(reply.getNote()));
                row.put("问卷", reply.getAssignedFormKey());
                rows.add(row);
            }
            if (result.getRecords().size() < 100) break;
        }
        access.audit(formKey, "REPLY_EXPORT", null, "导出回复" + rows.size() + "条");
        SurveyRecipientService.writeExcel(
                response, rows, Arrays.asList("手机号", "回复内容", "回复时间", "处理状态", "备注"));
    }

    private QueryWrapper<SurveyDelivery> deliveryFilter(
            String formKey, Long batchId, String groupName, String company) {
        QueryWrapper<SurveyDelivery> query =
                new QueryWrapper<SurveyDelivery>().eq("form_key", formKey);
        if (batchId != null) {
            SurveyBatch batch = batchService.getBatch(batchId);
            check(formKey.equals(batch.getFormKey()), "批次不属于当前问卷");
            query.eq("batch_id", batchId);
        }
        filter(query, groupName, company);
        return query;
    }

    private void filter(QueryWrapper<?> query, String groupName, String company) {
        if (groupName != null && !groupName.isEmpty()) query.eq("group_name", groupName);
        if (company != null && !company.isEmpty()) query.eq("company", company);
    }

    private long count(List<SurveyDelivery> rows, String dimension, String value) {
        return rows.stream()
                .filter(
                        r ->
                                value.equals(
                                        "submit".equals(dimension)
                                                ? r.getSubmitStatus()
                                                : "send".equals(dimension)
                                                        ? r.getSendStatus()
                                                        : r.getDeliveryStatus()))
                .count();
    }
}
