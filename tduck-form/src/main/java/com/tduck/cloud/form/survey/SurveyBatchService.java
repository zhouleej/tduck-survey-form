package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.baomidou.mybatisplus.extension.service.impl.ServiceImpl;
import com.tduck.cloud.common.util.SecurityUtils;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.*;
import java.util.*;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class SurveyBatchService extends ServiceImpl<SurveyDeliveryMapper, SurveyDelivery> {
    private final SurveyBatchMapper batches;
    private final SurveyRecipientMapper recipients;
    private final SurveySettingMapper settings;
    private final SurveySettingsService settingsService;
    private final SurveyTemplateService templates;
    private final SurveyInvitationService invitations;
    private final SurveyInvitationMapper invitationMapper;
    private final SurveySuppressionMapper suppressions;
    private final SurveyProgressMapper progress;
    private final SurveyGateway gateway;
    private final SurveyAccess access;

    public SurveyBatch getBatch(Long id) {
        SurveyBatch batch = batches.selectById(id);
        check(batch != null, "邀请批次不存在");
        access.requireForm(batch.getFormKey());
        return batch;
    }

    public Page<SurveyBatch> listBatches(String formKey, int page, int size, String status) {
        access.requireForm(formKey);
        check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        return batches.selectPage(
                new Page<>(page, size),
                new QueryWrapper<SurveyBatch>()
                        .eq("form_key", formKey)
                        .eq(status != null && !status.isEmpty(), "status", status)
                        .orderByDesc("id"));
    }

    public Map<String, Object> deliveries(
            Long id,
            int page,
            int size,
            String keyword,
            String submitStatus,
            String sendStatus,
            String deliveryStatus,
            String participation) {
        SurveyBatch batch = getBatch(id);
        check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        QueryWrapper<SurveyDelivery> query = new QueryWrapper<SurveyDelivery>().eq("batch_id", id);
        if (keyword != null && !keyword.isEmpty())
            query.and(
                    q ->
                            q.like("phone", keyword)
                                    .or()
                                    .like("name", keyword)
                                    .or()
                                    .like("company", keyword));
        if (submitStatus != null && !submitStatus.isEmpty())
            query.eq("submit_status", submitStatus);
        if (sendStatus != null && !sendStatus.isEmpty()) query.eq("send_status", sendStatus);
        if (deliveryStatus != null && !deliveryStatus.isEmpty())
            query.eq("delivery_status", deliveryStatus);
        Set<Long> completed =
                "TRACKED".equals(batch.getMode())
                        ? new HashSet<>(progress.completed(batch.getFormKey()))
                        : Collections.emptySet();
        Set<Long> visited =
                "TRACKED".equals(batch.getMode())
                        ? new HashSet<>(progress.visited(batch.getFormKey()))
                        : Collections.emptySet();
        applyParticipation(
                query, batch.getMode(), participation, completed, visited, "recipient_id");
        Page<SurveyDelivery> result = page(new Page<>(page, size), query.orderByAsc("id"));
        List<Map<String, Object>> rows =
                result.getRecords().stream()
                        .map(
                                row -> {
                                    Map<String, Object> value =
                                            com.tduck.cloud.common.util.JsonUtils.jsonToObj(
                                                    com.tduck.cloud.common.util.JsonUtils.objToJson(
                                                            row),
                                                    Map.class);
                                    // 对外序列化遵循已有Long ID及JsonIgnore约定，再显式剔除内部变量。
                                    value.remove("variablesJson");
                                    value.put(
                                            "participation",
                                            "TRACKED".equals(batch.getMode())
                                                    ? completed.contains(row.getRecipientId())
                                                            ? "COMPLETED"
                                                            : visited.contains(row.getRecipientId())
                                                                    ? "VISITED"
                                                                    : "NOT_VISITED"
                                                    : null);
                                    return value;
                                })
                        .collect(Collectors.toList());
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("records", rows);
        response.put("total", result.getTotal());
        return response;
    }

    public List<Map<String, Object>> answers(String formKey, Long recipientId) {
        access.requireForm(formKey);
        SurveyRecipient row = recipients.selectById(recipientId);
        check(row != null && formKey.equals(row.getFormKey()), "调研对象不存在");
        check("TRACKED".equals(settingsService.ensure(formKey).getMode()), "匿名调研不提供对象答卷关联");
        List<Map<String, Object>> rows = progress.answers(formKey, recipientId);
        for (Map<String, Object> answerRow : rows)
            if (answerRow.get("id") != null) answerRow.put("id", answerRow.get("id").toString());
        return rows;
    }

    public Map<String, Object> recipientProgress(String formKey, List<Long> ids) {
        access.requireForm(formKey);
        check(
                ids != null && ids.size() <= 100 && ids.stream().allMatch(v -> v != null && v > 0),
                "每次最多查询100个有效对象");
        Map<String, Object> result = new LinkedHashMap<>();
        if (!"TRACKED".equals(settingsService.get(formKey).getMode()) || ids.isEmpty())
            return result;
        Set<Long> completed = new HashSet<>(progress.completed(formKey)),
                visited = new HashSet<>(progress.visited(formKey));
        List<SurveyInvitation> links =
                invitationMapper.selectList(
                        new QueryWrapper<SurveyInvitation>()
                                .eq("form_key", formKey)
                                .in("recipient_id", ids)
                                .select("recipient_id", "first_visit_at", "last_visit_at"));
        Set<Long> owned =
                recipients
                        .selectList(
                                new QueryWrapper<SurveyRecipient>()
                                        .eq("form_key", formKey)
                                        .in("id", ids)
                                        .select("id"))
                        .stream()
                        .map(SurveyRecipient::getId)
                        .collect(Collectors.toSet());
        for (Long id : ids) {
            if (!owned.contains(id)) continue;
            Map<String, Object> row = new LinkedHashMap<>();
            row.put(
                    "state",
                    completed.contains(id)
                            ? "COMPLETED"
                            : visited.contains(id) ? "VISITED" : "NOT_VISITED");
            LocalDateTime first =
                    links.stream()
                            .filter(
                                    v ->
                                            id.equals(v.getRecipientId())
                                                    && v.getFirstVisitAt() != null)
                            .map(SurveyInvitation::getFirstVisitAt)
                            .min(LocalDateTime::compareTo)
                            .orElse(null);
            LocalDateTime last =
                    links.stream()
                            .filter(
                                    v ->
                                            id.equals(v.getRecipientId())
                                                    && v.getLastVisitAt() != null)
                            .map(SurveyInvitation::getLastVisitAt)
                            .max(LocalDateTime::compareTo)
                            .orElse(null);
            row.put("firstVisitAt", first == null ? null : first.toString().replace('T', ' '));
            row.put("lastVisitAt", last == null ? null : last.toString().replace('T', ' '));
            result.put(id.toString(), row);
        }
        return result;
    }

    public Map<String, Object> preview(SurveyBatchRequest request) {
        UserFormEntity form = access.requireReleased(request.getFormKey());
        SurveySetting setting = settingsService.get(request.getFormKey());
        SurveySmsTemplate template = templates.get(request.getTemplateId(), false);
        List<SurveyRecipient> selected = select(request, setting.getMode());
        List<SurveyRecipient> eligible = new ArrayList<>();
        Map<String, Integer> excluded = new LinkedHashMap<>();
        String account = gateway.client().accountKey();
        Map<Long, String> reasons =
                exclusionReasons(request.getFormKey(), selected, request.getKind(), null, account);
        for (SurveyRecipient row : selected) {
            String reason = reasons.get(row.getId());
            if (reason != null) excluded.put(reason, excluded.getOrDefault(reason, 0) + 1);
            else eligible.add(row);
        }
        String token = SurveyInvitationService.token(),
                link = gateway.publicBaseUrl() + "/i/" + token;
        List<Map<String, Object>> samples = new ArrayList<>();
        List<Map<String, Object>> invalidSamples = new ArrayList<>();
        SurveyTemplateRenderer.Compiled renderer = SurveyTemplateRenderer.compile(template);
        int invalid = 0;
        long referenceSegments = 0;
        boolean hasLink = true;
        for (SurveyRecipient recipient : eligible) {
            try {
                Map<String, Object> variables = renderer.variables(form, recipient, link, token);
                String content = SurveyTemplateRenderer.render(template, variables);
                int characters = content.codePointCount(0, content.length());
                referenceSegments += characters <= 70 ? 1 : (characters + 66L) / 67L;
                hasLink &= content.contains(link);
                if (samples.size() < 3) {
                    Map<String, Object> sample = new LinkedHashMap<>();
                    sample.put("phone", recipient.getPhone());
                    sample.put("content", content);
                    sample.put("link", link);
                    samples.add(sample);
                }
            } catch (Exception e) {
                invalid++;
                if (invalidSamples.size() < 20) {
                    Map<String, Object> error = new LinkedHashMap<>();
                    error.put("phone", recipient.getPhone());
                    error.put(
                            "reason",
                            e instanceof com.tduck.cloud.common.exception.BaseException
                                    ? e.getMessage()
                                    : "变量配置不可用");
                    invalidSamples.add(error);
                }
            }
        }
        Map<String, Object> response = new LinkedHashMap<>();
        response.put("total", selected.size());
        response.put("eligible", eligible.size());
        response.put(
                "eligibleIds",
                eligible.stream().map(SurveyRecipient::getId).collect(Collectors.toList()));
        response.put("selectionHash", selectionHash(eligible, template, form, setting.getMode()));
        response.put("referenceSegments", referenceSegments);
        response.put("excluded", excluded);
        response.put("invalidVariables", invalid);
        response.put("invalidSamples", invalidSamples);
        response.put("mode", setting.getMode());
        response.put("samples", samples);
        response.put("hasInvitationLink", hasLink);
        response.put("templateVersion", template.getVersion());
        response.put("approved", SurveyTemplateService.approved(template));
        response.put("allCarriersApproved", SurveyTemplateService.allCarriersApproved(template));
        response.put(
                "audit",
                JSONUtil.parseObj(
                        template.getAuditJson() == null ? "{}" : template.getAuditJson()));
        response.put("enabled", gateway.capabilities().get("enabled"));
        return response;
    }

    @Transactional(
            rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public SurveyBatch create(SurveyBatchRequest request) {
        access.requireForm(request.getFormKey());
        check(
                request.getRequestKey() != null
                        && request.getRequestKey().matches("[A-Za-z0-9_-]{8,64}"),
                "缺少有效幂等键");
        if (request.getRecipientIds() != null) {
            check(
                    request.getRecipientIds().size() <= 10000
                            && request.getRecipientIds().stream().allMatch(v -> v != null && v > 0),
                    "对象编号无效或超过1万条");
            request.setRecipientIds(
                    request.getRecipientIds().stream()
                            .distinct()
                            .sorted()
                            .collect(Collectors.toList()));
        }
        String hash = DigestUtil.sha256Hex(JSONUtil.toJsonStr(request));
        SurveyBatch prior =
                batches.selectOne(
                        new QueryWrapper<SurveyBatch>()
                                .eq("form_key", request.getFormKey())
                                .eq("request_key", request.getRequestKey()));
        if (prior != null) {
            check(hash.equals(prior.getRequestHash()), "同一幂等键不能提交不同内容");
            return prior;
        }
        access.requireReleased(request.getFormKey());
        gateway.requireEnabled();
        settingsService.ensure(request.getFormKey());
        SurveySetting setting =
                settings.selectOne(
                        new QueryWrapper<SurveySetting>()
                                .eq("form_key", request.getFormKey())
                                .last("FOR UPDATE"));
        prior =
                batches.selectOne(
                        new QueryWrapper<SurveyBatch>()
                                .eq("form_key", request.getFormKey())
                                .eq("request_key", request.getRequestKey())
                                .last("FOR UPDATE"));
        if (prior != null) {
            check(hash.equals(prior.getRequestHash()), "同一幂等键不能提交不同内容");
            return prior;
        }
        check(
                request.getRecipientIds() != null
                        && !request.getRecipientIds().isEmpty()
                        && request.getExpectedSelectionHash() != null,
                "请先预览并确认本次邀请对象");
        List<SurveyRecipient> frozen =
                recipients.selectList(
                        new QueryWrapper<SurveyRecipient>()
                                .eq("form_key", request.getFormKey())
                                .in("id", request.getRecipientIds())
                                .orderByAsc("id")
                                .last("FOR UPDATE"));
        check(frozen.size() == request.getRecipientIds().size(), "对象名单已变化，请重新预览");
        SurveySmsTemplate previewTemplate = templates.get(request.getTemplateId(), false);
        check(
                request.getExpectedSelectionHash()
                        .equals(
                                selectionHash(
                                        frozen,
                                        previewTemplate,
                                        access.existingForm(request.getFormKey()),
                                        setting.getMode())),
                "名单资料、模板或发送配置已变化，请重新预览");
        Map<String, Object> preview = preview(request);
        check(Boolean.TRUE.equals(preview.get("approved")), "模板尚未通过云信审核，请先查询审核状态");
        check(
                request.getExpectedTemplateVersion() != null
                        && request.getExpectedTemplateVersion()
                                .equals(preview.get("templateVersion")),
                "模板已变化，请重新预览");
        check(
                Boolean.TRUE.equals(preview.get("allCarriersApproved"))
                        || request.isCoverageAccepted(),
                "请确认当前模板运营商覆盖范围");
        check(((Number) preview.get("invalidVariables")).intValue() == 0, "部分对象的变量缺失或超长，请修正后重试");
        check(
                Boolean.TRUE.equals(preview.get("hasInvitationLink"))
                        || ("ANONYMOUS".equals(setting.getMode()) && request.isUntrackedAccepted()),
                "模板没有完整邀请链接，无法跟踪；匿名发送需确认不提供批次回收归因");
        List<SurveyRecipient> selected = select(request, setting.getMode());
        String account = gateway.client().accountKey();
        Map<Long, String> reasons =
                exclusionReasons(request.getFormKey(), selected, request.getKind(), null, account);
        List<SurveyRecipient> eligible =
                selected.stream()
                        .filter(r -> !reasons.containsKey(r.getId()))
                        .collect(Collectors.toList());
        check(!eligible.isEmpty() && eligible.size() <= 10000, "没有符合条件的对象，或批次超过1万人");
        SurveySmsTemplate template = templates.get(request.getTemplateId(), false);
        check(template.getVersion().equals(request.getExpectedTemplateVersion()), "模板已变化，请重新预览");
        LocalDateTime now = now();
        check(
                request.getScheduledAt() == null
                        || !request.getScheduledAt().isBefore(now.minusMinutes(1)),
                "预约时间已过，请重新选择");
        String name =
                request.getName() == null || request.getName().trim().isEmpty()
                        ? "问卷邀请"
                        : request.getName().trim();
        check(name.length() <= 100, "批次名称过长");
        SurveyBatch batch = new SurveyBatch();
        batch.setFormKey(request.getFormKey());
        batch.setName(name);
        batch.setKind(request.getKind());
        batch.setMode(setting.getMode());
        batch.setTemplateId(template.getId());
        batch.setTemplateVersion(template.getVersion());
        cn.hutool.json.JSONObject snapshot = JSONUtil.parseObj(template);
        if (request.getLegacyIntentHash() != null)
            snapshot.set("_legacyIntentHash", request.getLegacyIntentHash());
        batch.setTemplateSnapshot(snapshot.toString());
        batch.setAccountKey(account);
        batch.setConfigVersion(gateway.client().configurationVersion());
        batch.setStatus("PENDING");
        batch.setScheduledAt(request.getScheduledAt() == null ? now : request.getScheduledAt());
        batch.setOwnerId(SecurityUtils.getUserId());
        batch.setRequestKey(request.getRequestKey());
        batch.setRequestHash(hash);
        batch.setRecipientCount(eligible.size());
        batch.setExcludedCount(selected.size() - eligible.size());
        batch.setSourceBatchId(request.getSourceBatchId());
        batch.setCoverageAccepted(request.isCoverageAccepted());
        batches.insert(batch);
        SurveyInvitation shared =
                "ANONYMOUS".equals(setting.getMode())
                        ? invitations.create(
                                batch.getFormKey(), batch.getId(), null, setting.getMode())
                        : null;
        UserFormEntity form = access.existingForm(batch.getFormKey());
        String base = gateway.publicBaseUrl();
        List<SurveyDelivery> rows = new ArrayList<>();
        SurveyTemplateRenderer.Compiled renderer = SurveyTemplateRenderer.compile(template);
        for (SurveyRecipient recipient : eligible) {
            SurveyInvitation invitation =
                    shared == null
                            ? invitations.create(
                                    batch.getFormKey(),
                                    batch.getId(),
                                    recipient.getId(),
                                    setting.getMode())
                            : shared;
            Map<String, Object> variables =
                    renderer.variables(
                            form,
                            recipient,
                            base + "/i/" + invitation.getToken(),
                            invitation.getToken());
            SurveyDelivery row = new SurveyDelivery();
            row.setFormKey(batch.getFormKey());
            row.setBatchId(batch.getId());
            row.setRecipientId(recipient.getId());
            row.setInvitationId(invitation.getId());
            row.setPhone(recipient.getPhone());
            row.setName(recipient.getName());
            row.setCompany(recipient.getCompany());
            row.setGroupName(recipient.getGroupName());
            row.setVariablesJson(JSONUtil.toJsonStr(variables));
            row.setRenderedContent(SurveyTemplateRenderer.render(template, variables));
            row.setSubmitStatus("PENDING");
            row.setSendStatus("PENDING");
            row.setDeliveryStatus("WAITING");
            rows.add(row);
        }
        saveBatch(rows, 500);
        setting.setModeLocked(true);
        settings.updateById(setting);
        access.audit(
                batch.getFormKey(),
                "BATCH_CREATE",
                batch.getId(),
                "创建" + batch.getKind() + "批次，对象数" + rows.size());
        return batch;
    }

    public List<SurveyRecipient> select(SurveyBatchRequest request, String mode) {
        check(
                Arrays.asList("INVITE", "REMINDER", "RETRY", "REINVITE")
                        .contains(request.getKind()),
                "邀请类型无效");
        if ("REMINDER".equals(request.getKind()))
            check("TRACKED".equals(mode), "匿名调研不能精准筛选未填写对象，请使用再次邀请");
        QueryWrapper<SurveyRecipient> query =
                new QueryWrapper<SurveyRecipient>().eq("form_key", request.getFormKey());
        if (request.getRecipientIds() != null) {
            check(request.getRecipientIds().stream().allMatch(v -> v != null && v > 0), "对象编号无效");
            check(
                    !request.getRecipientIds().isEmpty()
                            && request.getRecipientIds().size() <= 10000,
                    "请选择1至10000个对象");
            query.in("id", request.getRecipientIds());
        }
        if (request.getGroupName() != null && !request.getGroupName().isEmpty())
            query.eq("group_name", request.getGroupName());
        if (request.getCompany() != null && !request.getCompany().isEmpty())
            query.eq("company", request.getCompany());
        if (request.getSourceBatchId() != null) {
            SurveyBatch source = getBatch(request.getSourceBatchId());
            check(request.getFormKey().equals(source.getFormKey()), "来源批次与问卷不匹配");
            QueryWrapper<SurveyDelivery> previous =
                    new QueryWrapper<SurveyDelivery>().eq("batch_id", source.getId());
            if ("RETRY".equals(request.getKind()))
                previous.and(
                        q ->
                                q.eq("submit_status", "REJECTED")
                                        .or()
                                        .eq("send_status", "FAILED")
                                        .or()
                                        .eq("delivery_status", "FAILED"));
            List<Long> ids =
                    list(previous).stream()
                            .map(SurveyDelivery::getRecipientId)
                            .collect(Collectors.toList());
            if (ids.isEmpty()) return Collections.emptyList();
            query.in("id", ids);
        } else check(!"RETRY".equals(request.getKind()), "再次发送失败对象须指定来源批次");
        if (request.getParticipation() != null && !request.getParticipation().isEmpty()) {
            Set<Long> completed = new HashSet<>(progress.completed(request.getFormKey())),
                    visited = new HashSet<>(progress.visited(request.getFormKey()));
            applyParticipation(query, mode, request.getParticipation(), completed, visited, "id");
        }
        query.orderByAsc("id").last("LIMIT 10001");
        List<SurveyRecipient> selected = recipients.selectList(query);
        check(selected.size() <= 10000, "单个批次最多选择1万人，请按分组拆分");
        return selected;
    }

    public Map<Long, String> exclusionReasons(
            String formKey,
            List<SurveyRecipient> selected,
            String kind,
            Long currentBatch,
            String account) {
        Set<Long> completed =
                "REMINDER".equals(kind)
                        ? new HashSet<>(progress.completed(formKey))
                        : Collections.emptySet();
        Set<String> blocked =
                suppressions
                        .selectList(
                                new QueryWrapper<SurveySuppression>()
                                        .eq("account_key", account)
                                        .eq("active", true))
                        .stream()
                        .map(SurveySuppression::getPhone)
                        .collect(Collectors.toSet());
        List<SurveyDelivery> history =
                list(
                        new QueryWrapper<SurveyDelivery>()
                                .eq("form_key", formKey)
                                .ne(currentBatch != null, "batch_id", currentBatch)
                                .and(
                                        q ->
                                                q.ge("submitted_at", now().minusHours(24))
                                                        .or()
                                                        .in(
                                                                "submit_status",
                                                                Arrays.asList(
                                                                        "PENDING",
                                                                        "SUBMITTING",
                                                                        "UNKNOWN"))));
        Set<String> active = new HashSet<>(), recent = new HashSet<>();
        for (SurveyDelivery row : history) {
            if (Arrays.asList("PENDING", "SUBMITTING", "UNKNOWN").contains(row.getSubmitStatus()))
                active.add(row.getPhone());
            if (row.getSubmittedAt() != null && row.getSubmittedAt().isAfter(now().minusHours(24)))
                recent.add(row.getPhone());
        }
        Map<Long, String> reasons = new HashMap<>();
        for (SurveyRecipient row : selected) {
            String reason = null;
            if (Boolean.TRUE.equals(row.getDisabled())) reason = "联系人已停用";
            else if (blocked.contains(row.getPhone())) reason = "已设置拒收";
            else if (completed.contains(row.getId())) reason = "已完成";
            else if (active.contains(row.getPhone())) reason = "存在待发送或受理未知记录";
            else if (recent.contains(row.getPhone())) reason = "距上次发送不足24小时";
            if (reason != null) reasons.put(row.getId(), reason);
        }
        return reasons;
    }

    @Transactional(rollbackFor = Exception.class)
    public SurveyBatch action(Long id, String action) {
        SurveyBatch owned = getBatch(id);
        SurveyBatch batch =
                batches.selectOne(new QueryWrapper<SurveyBatch>().eq("id", id).last("FOR UPDATE"));
        check(
                Arrays.asList("PENDING", "RUNNING", "PAUSED").contains(batch.getStatus()),
                "批次已结束，无法执行此操作");
        if ("cancel".equals(action)) {
            batch.setStatus("CANCELED");
            batch.setReason("操作人取消待发送部分，已提交短信无法撤回");
            update(
                    new UpdateWrapper<SurveyDelivery>()
                            .eq("batch_id", id)
                            .eq("submit_status", "PENDING")
                            .set("submit_status", "CANCELED")
                            .set("reason", "操作人取消"));
        } else if ("pause".equals(action)) {
            batch.setStatus("PAUSED");
            batch.setReason("操作人暂停待发送队列");
        } else if ("resume".equals(action)) {
            access.requireReleased(batch.getFormKey());
            gateway.requireEnabled();
            check("PAUSED".equals(batch.getStatus()), "只有暂停的批次可以继续");
            SurveySmsTemplate template = templates.get(batch.getTemplateId(), false);
            check(
                    batch.getTemplateVersion().equals(template.getVersion())
                            && SurveyTemplateService.approved(template),
                    "模板已变化或未通过审核，请取消待发送部分并重新预览创建");
            check(batch.getAccountKey().equals(gateway.client().accountKey()), "云信账户已改变，不能继续旧批次");
            batch.setConfigVersion(gateway.client().configurationVersion());
            batch.setStatus("PENDING");
            batch.setReason("");
        } else check(false, "批次操作无效");
        batches.updateById(batch);
        access.audit(
                owned.getFormKey(), "BATCH_" + action.toUpperCase(Locale.ROOT), id, "更新本地待发送队列");
        return batch;
    }

    public static void applyParticipation(
            QueryWrapper<?> query,
            String mode,
            String state,
            Set<Long> completed,
            Set<Long> visited,
            String column) {
        if (state == null || state.isEmpty()) return;
        check("TRACKED".equals(mode), "匿名调研不提供逐人填写筛选");
        Set<Long> ids = new HashSet<>();
        if ("COMPLETED".equals(state)) {
            ids.addAll(completed);
            if (ids.isEmpty()) query.apply("1=0");
            else query.in(column, ids);
        } else if ("NOT_COMPLETED".equals(state)) {
            if (!completed.isEmpty()) query.notIn(column, completed);
        } else if ("VISITED".equals(state)) {
            ids.addAll(visited);
            ids.removeAll(completed);
            if (ids.isEmpty()) query.apply("1=0");
            else query.in(column, ids);
        } else if ("NOT_VISITED".equals(state)) {
            ids.addAll(completed);
            ids.addAll(visited);
            if (!ids.isEmpty()) query.notIn(column, ids);
        } else check(false, "填写状态筛选无效");
    }

    public static LocalDateTime now() {
        return LocalDateTime.now(ZoneId.of("Asia/Shanghai")).withNano(0);
    }

    private String selectionHash(
            List<SurveyRecipient> rows,
            SurveySmsTemplate template,
            UserFormEntity form,
            String mode) {
        List<Map<String, Object>> snapshot = new ArrayList<>();
        for (SurveyRecipient row : rows) {
            Map<String, Object> value = new TreeMap<>();
            value.put("id", row.getId());
            value.put("phone", row.getPhone());
            value.put("name", row.getName());
            value.put("company", row.getCompany());
            value.put("group", row.getGroupName());
            snapshot.add(value);
        }
        return DigestUtil.sha256Hex(
                form.getFormKey()
                        + "|"
                        + form.getTextName()
                        + "|"
                        + mode
                        + "|"
                        + template.getVersion()
                        + "|"
                        + template.getAuditJson()
                        + "|"
                        + gateway.client().configurationVersion()
                        + "|"
                        + JSONUtil.toJsonStr(snapshot));
    }

    public SurveyBatchRequest reminderRequest(Long batchId, SurveyBatchRequest request) {
        SurveyBatch source = getBatch(batchId);
        check(
                request.getFormKey() == null || source.getFormKey().equals(request.getFormKey()),
                "问卷与来源批次不匹配");
        request.setFormKey(source.getFormKey());
        request.setSourceBatchId(source.getId());
        request.setKind("TRACKED".equals(source.getMode()) ? "REMINDER" : "REINVITE");
        if ("REMINDER".equals(request.getKind())
                && (request.getParticipation() == null || request.getParticipation().isEmpty()))
            request.setParticipation("NOT_COMPLETED");
        return request;
    }

    @Transactional(rollbackFor = Exception.class)
    public void resolveUnknown(Long deliveryId, String outcome, String taskId, String evidence) {
        access.admin();
        check(
                evidence != null && !evidence.trim().isEmpty() && evidence.length() <= 500,
                "请填写在云信核实的依据（最多500字符）");
        SurveyDelivery delivery =
                baseMapper.selectOne(
                        new QueryWrapper<SurveyDelivery>().eq("id", deliveryId).last("FOR UPDATE"));
        check(delivery != null, "发送明细不存在");
        SurveyBatch batch = getBatch(delivery.getBatchId());
        check(
                batch.getAccountKey().equals(gateway.client().accountKey())
                        && "UNKNOWN".equals(delivery.getSubmitStatus()),
                "仅可核对当前账户的受理未知记录");
        check(Arrays.asList("ACCEPTED", "NOT_ACCEPTED").contains(outcome), "核实结果无效");
        if ("ACCEPTED".equals(outcome))
            check(taskId != null && taskId.matches("[A-Za-z0-9_-]{1,64}"), "请填写已核实的云信任务号");
        UpdateWrapper<SurveyDelivery> update =
                new UpdateWrapper<SurveyDelivery>()
                        .eq("id", deliveryId)
                        .eq("submit_status", "UNKNOWN")
                        .set("submit_status", "ACCEPTED".equals(outcome) ? "ACCEPTED" : "REJECTED")
                        .set("send_status", "ACCEPTED".equals(outcome) ? "PENDING" : "FAILED")
                        .set("delivery_status", "ACCEPTED".equals(outcome) ? "WAITING" : "NOT_SENT")
                        .set("reason", "管理员核实：" + evidence);
        if ("ACCEPTED".equals(outcome)) update.set("provider_task_id", taskId);
        baseMapper.update(null, update);
        access.audit(
                batch.getFormKey(),
                "SUBMISSION_RESOLVE",
                deliveryId,
                "人工核实" + outcome + "：" + evidence);
    }
}
