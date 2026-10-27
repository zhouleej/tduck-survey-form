package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.baomidou.mybatisplus.extension.plugins.pagination.Page;
import com.tduck.cloud.common.sms.YunxinGatewayException;
import com.tduck.cloud.common.util.SecurityUtils;
import com.tduck.cloud.form.survey.entity.SurveySmsTemplate;
import com.tduck.cloud.form.survey.mapper.SurveySmsTemplateMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SurveyTemplateService {
    private final SurveySmsTemplateMapper mapper;
    private final SurveyGateway gateway;
    private final SurveyAccess access;

    public Page<SurveySmsTemplate> list(int page, int size) {
        check(page > 0 && size > 0 && size <= 100, "分页范围无效");
        QueryWrapper<SurveySmsTemplate> query =
                new QueryWrapper<SurveySmsTemplate>()
                        .eq("account_key", gateway.client().accountKey());
        if (!SecurityUtils.isAdmin())
            query.and(q -> q.eq("owner_id", SecurityUtils.getUserId()).or().eq("shared", true));
        return mapper.selectPage(new Page<>(page, size), query.orderByDesc("id"));
    }

    public SurveySmsTemplate get(Long id, boolean edit) {
        SurveySmsTemplate template = mapper.selectById(id);
        check(
                template != null && gateway.client().accountKey().equals(template.getAccountKey()),
                "模板不存在或不属于当前云信账户");
        check(
                SecurityUtils.isAdmin()
                        || Objects.equals(template.getOwnerId(), SecurityUtils.getUserId())
                        || (!edit && Boolean.TRUE.equals(template.getShared())),
                "无模板权限");
        return template;
    }

    @Transactional(rollbackFor = Exception.class)
    public SurveySmsTemplate save(SurveySmsTemplate request) {
        check(
                request.getName() != null
                        && !request.getName().trim().isEmpty()
                        && request.getName().length() <= 100,
                "模板名称须为1至100字符");
        check(
                request.getProviderTemplateId() == null
                        || request.getProviderTemplateId().matches("[A-Za-z0-9_-]{1,64}"),
                "模板ID格式无效");
        SurveyTemplateRenderer.validate(request);
        SurveySmsTemplate current;
        if (request.getId() == null) {
            current = new SurveySmsTemplate();
            current.setOwnerId(SecurityUtils.getUserId());
            current.setAccountKey(gateway.client().accountKey());
        } else {
            get(request.getId(), true);
            current =
                    mapper.selectOne(
                            new QueryWrapper<SurveySmsTemplate>()
                                    .eq("id", request.getId())
                                    .last("FOR UPDATE"));
            check(
                    !"SUBMITTING".equals(current.getApplicationState())
                            || (current.getUpdateTime() != null
                                    && current.getUpdateTime()
                                            .isBefore(SurveyBatchService.now().minusMinutes(3))),
                    "模板正在申请，请勿修改");
        }
        check(!Boolean.TRUE.equals(request.getShared()) || SecurityUtils.isAdmin(), "只有管理员可发布共享模板");
        current.setName(request.getName().trim());
        current.setContent(request.getContent());
        current.setVariable(request.getVariable());
        current.setMappingJson(JSONUtil.toJsonStr(JSONUtil.parseObj(request.getMappingJson())));
        current.setShared(Boolean.TRUE.equals(request.getShared()));
        current.setProviderTemplateId(request.getProviderTemplateId());
        boolean unresolved =
                Arrays.asList("SUBMITTING", "SUBMISSION_UNKNOWN")
                        .contains(current.getApplicationState());
        current.setApplicationState(
                request.getProviderTemplateId() != null
                        ? "BOUND"
                        : unresolved ? "SUBMISSION_UNKNOWN" : "DRAFT");
        current.setAuditJson("{}");
        current.setAuditedAt(null);
        current.setVersion(
                DigestUtil.sha256Hex(
                        current.getContent()
                                + "|"
                                + current.getMappingJson()
                                + "|"
                                + current.getProviderTemplateId()
                                + "|"
                                + UUID.randomUUID()));
        if (current.getId() == null) mapper.insert(current);
        else
            mapper.update(
                    null,
                    new UpdateWrapper<SurveySmsTemplate>()
                            .eq("id", current.getId())
                            .set("name", current.getName())
                            .set("content", current.getContent())
                            .set("variable", current.getVariable())
                            .set("mapping_json", current.getMappingJson())
                            .set("shared", current.getShared())
                            .set("provider_template_id", current.getProviderTemplateId())
                            .set("application_state", current.getApplicationState())
                            .set("audit_json", "{}")
                            .set("audited_at", null)
                            .set("version", current.getVersion()));
        access.audit(null, "TEMPLATE_SAVE", current.getId(), "保存模板并等待重新核对审核");
        return current;
    }

    public SurveySmsTemplate apply(Long id) {
        SurveySmsTemplate template = get(id, true);
        check(
                template.getProviderTemplateId() == null
                        && "DRAFT".equals(template.getApplicationState()),
                "模板已申请或结果未知，请先核实，不可重复申请");
        check(template.getContent().matches("(?s)^【[^】]+】.+"), "新模板正文须以【短信签名】开头");
        check(gateway.properties().isYunxinTemplateCreateEnabled(), "模板申请能力尚未启用");
        check(
                mapper.update(
                                null,
                                new UpdateWrapper<SurveySmsTemplate>()
                                        .eq("id", id)
                                        .eq("version", template.getVersion())
                                        .eq("application_state", "DRAFT")
                                        .set("application_state", "SUBMITTING"))
                        == 1,
                "模板正在处理或已改变，请刷新");
        try {
            JSONObject response =
                    gateway.client()
                            .createTextTemplate(
                                    template.getName(),
                                    template.getContent(),
                                    Boolean.TRUE.equals(template.getVariable()));
            String providerId = response.getStr("data");
            if (providerId == null || !providerId.matches("[A-Za-z0-9_-]{1,64}"))
                throw new YunxinGatewayException("模板申请已返回，但模板ID缺失，请在云信核实", true, null);
            mapper.update(
                    null,
                    new UpdateWrapper<SurveySmsTemplate>()
                            .eq("id", id)
                            .eq("version", template.getVersion())
                            .set("provider_template_id", providerId)
                            .set("application_state", "APPLIED"));
            access.audit(null, "TEMPLATE_APPLY", id, "模板申请已受理");
        } catch (Exception e) {
            boolean unknown =
                    !(e instanceof YunxinGatewayException)
                            || ((YunxinGatewayException) e).isUncertain();
            mapper.update(
                    null,
                    new UpdateWrapper<SurveySmsTemplate>()
                            .eq("id", id)
                            .eq("version", template.getVersion())
                            .set("application_state", unknown ? "SUBMISSION_UNKNOWN" : "DRAFT"));
            throw e;
        }
        return get(id, false);
    }

    public SurveySmsTemplate refreshAudit(Long id) {
        SurveySmsTemplate template = get(id, true);
        refreshAuditInternal(template);
        return get(id, false);
    }

    public void refreshAuditInternal(SurveySmsTemplate template) {
        check(template.getProviderTemplateId() != null, "请先绑定或申请模板");
        JSONObject response = gateway.client().templateStatus(template.getProviderTemplateId());
        Object data = response.get("data");
        if (data instanceof cn.hutool.json.JSONArray) {
            cn.hutool.json.JSONArray list = (cn.hutool.json.JSONArray) data;
            check(list.size() == 1, "审核响应无法唯一关联模板");
            data = list.getJSONObject(0);
        }
        check(data instanceof JSONObject || data instanceof Map, "审核响应缺少结构化状态");
        JSONObject audit = JSONUtil.parseObj(data);
        // 只保留文档列出的审核状态和说明，避免把供应商额外字段暴露给普通用户。
        JSONObject safe = new JSONObject();
        for (String key :
                Arrays.asList(
                        "rx_statu",
                        "rx_desc",
                        "cmcc_statu",
                        "cmcc_desc",
                        "cucc_statu",
                        "cucc_desc",
                        "ctcc_statu",
                        "ctcc_desc")) safe.set(key, audit.getStr(key));
        mapper.update(
                null,
                new UpdateWrapper<SurveySmsTemplate>()
                        .eq("id", template.getId())
                        .eq("version", template.getVersion())
                        .set("audit_json", safe.toString())
                        .set("audited_at", LocalDateTime.now(ZoneId.of("Asia/Shanghai"))));
    }

    public static boolean approved(SurveySmsTemplate template) {
        return template.getProviderTemplateId() != null
                && template.getAuditJson() != null
                && "通过".equals(JSONUtil.parseObj(template.getAuditJson()).getStr("rx_statu"));
    }

    public static boolean allCarriersApproved(SurveySmsTemplate template) {
        JSONObject audit =
                JSONUtil.parseObj(template.getAuditJson() == null ? "{}" : template.getAuditJson());
        return Arrays.asList("cmcc_statu", "cucc_statu", "ctcc_statu").stream()
                .allMatch(k -> "正常可用".equals(audit.getStr(k)));
    }
}
