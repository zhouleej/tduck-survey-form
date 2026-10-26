package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tduck.cloud.common.util.SecurityUtils;
import com.tduck.cloud.form.request.FormSmsInviteRequest;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.util.*;
import java.util.stream.Collectors;

/** 旧请求进入同一持久化队列；不再存在绕过幂等及回执历史的直接发送路径。 */
@Service
@RequiredArgsConstructor
public class SurveyLegacyInviteService {
    private final SurveyBatchService batches;
    private final SurveyRecipientService recipients;
    private final SurveyRecipientMapper recipientMapper;
    private final SurveySmsTemplateMapper templateMapper;
    private final SurveySettingsService settings;
    private final SurveyAccess access;
    private final SurveyGateway gateway;
    private final SurveyBatchMapper batchMapper;

    @Transactional(
            rollbackFor = Exception.class,
            isolation = org.springframework.transaction.annotation.Isolation.READ_COMMITTED)
    public Map<String, Object> create(FormSmsInviteRequest request) {
        access.requireForm(request.getFormKey());
        List<String> intentPhones =
                request.getPhoneNumbers().stream()
                        .map(SurveyRecipientService::normalizePhone)
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());
        Map<String, String> intentParams = new TreeMap<>();
        if (request.getTemplateParams() != null)
            request.getTemplateParams()
                    .forEach((k, v) -> intentParams.put(k, Objects.toString(v, "")));
        String intentHash =
                DigestUtil.sha256Hex(
                        request.getFormKey()
                                + "|"
                                + request.getTemplateId()
                                + "|"
                                + JSONUtil.toJsonStr(intentPhones)
                                + "|"
                                + JSONUtil.toJsonStr(intentParams));
        String intentKey = request.getRequestKey();
        if (intentKey == null || intentKey.isEmpty())
            intentKey = "legacy_" + intentHash.substring(0, 48);
        SurveyBatch prior =
                batchMapper.selectOne(
                        new QueryWrapper<SurveyBatch>()
                                .eq("form_key", request.getFormKey())
                                .eq("request_key", intentKey));
        if (prior != null) {
            check(
                    intentHash.equals(
                            JSONUtil.parseObj(prior.getTemplateSnapshot())
                                    .getStr("_legacyIntentHash")),
                    "同一幂等键不能提交不同内容");
            return result(prior);
        }
        access.requireReleased(request.getFormKey());
        gateway.requireEnabled();
        check(
                "ANONYMOUS".equals(settings.get(request.getFormKey()).getMode()),
                "跟踪模式请使用新的短信邀请页面或批次接口");
        QueryWrapper<SurveySmsTemplate> query =
                new QueryWrapper<SurveySmsTemplate>()
                        .eq("account_key", gateway.client().accountKey())
                        .eq("provider_template_id", request.getTemplateId());
        if (!SecurityUtils.isAdmin())
            query.and(q -> q.eq("owner_id", SecurityUtils.getUserId()).or().eq("shared", true));
        List<SurveySmsTemplate> templates = templateMapper.selectList(query);
        check(templates.size() == 1, "请先唯一绑定并审核此短信模板；多个绑定时请使用新的邀请页面选择");
        SurveySmsTemplate template = templates.get(0);
        cn.hutool.json.JSONObject mappings = JSONUtil.parseObj(template.getMappingJson());
        for (String key : mappings.keySet())
            check(
                    Arrays.asList("FIXED", "FORM_KEY", "FORM_NAME")
                            .contains(mappings.getJSONObject(key).getStr("source")),
                    "此模板含逐号邀请变量，请使用新的邀请页面");
        Map<String, Object> expected =
                SurveyTemplateRenderer.variables(
                        template,
                        access.existingForm(request.getFormKey()),
                        new SurveyRecipient(),
                        "",
                        "");
        Map<String, String> supplied = new TreeMap<>();
        if (request.getTemplateParams() != null)
            request.getTemplateParams().forEach((k, v) -> supplied.put(k, Objects.toString(v, "")));
        Map<String, String> generated = new TreeMap<>();
        expected.forEach((k, v) -> generated.put(k, Objects.toString(v, "")));
        check(generated.equals(supplied), "请求变量与已绑定模板不一致，请重新预览");
        List<String> phones =
                request.getPhoneNumbers().stream()
                        .map(SurveyRecipientService::normalizePhone)
                        .distinct()
                        .sorted()
                        .collect(Collectors.toList());
        List<Map<String, Object>> rows =
                phones.stream()
                        .map(
                                phone -> {
                                    Map<String, Object> row = new HashMap<>();
                                    row.put("phone", phone);
                                    return row;
                                })
                        .collect(Collectors.toList());
        recipients.confirm(request.getFormKey(), rows, false);
        List<Long> ids =
                recipientMapper
                        .selectList(
                                new QueryWrapper<SurveyRecipient>()
                                        .eq("form_key", request.getFormKey())
                                        .in("phone", phones))
                        .stream()
                        .map(SurveyRecipient::getId)
                        .collect(Collectors.toList());
        SurveyBatchRequest batch = new SurveyBatchRequest();
        batch.setFormKey(request.getFormKey());
        batch.setName("兼容接口邀请");
        batch.setTemplateId(template.getId());
        batch.setRecipientIds(ids);
        batch.setExpectedTemplateVersion(template.getVersion());
        batch.setCoverageAccepted(false);
        batch.setUntrackedAccepted(true);
        String key = request.getRequestKey();
        if (key == null || key.isEmpty())
            key =
                    "legacy_"
                            + DigestUtil.sha256Hex(
                                            request.getFormKey()
                                                    + "|"
                                                    + request.getTemplateId()
                                                    + "|"
                                                    + JSONUtil.toJsonStr(phones)
                                                    + "|"
                                                    + JSONUtil.toJsonStr(supplied))
                                    .substring(0, 48);
        batch.setRequestKey(key);
        Map<String, Object> preview = batches.preview(batch);
        batch.setRecipientIds((List<Long>) preview.get("eligibleIds"));
        batch.setExpectedSelectionHash((String) preview.get("selectionHash"));
        batch.setLegacyIntentHash(intentHash);
        SurveyBatch created = batches.create(batch);
        return result(created);
    }

    private Map<String, Object> result(SurveyBatch created) {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("batchId", created.getId());
        result.put("recipientCount", created.getRecipientCount());
        result.put("status", created.getStatus());
        result.put("taskId", null);
        result.put("msgId", null);
        return result;
    }
}
