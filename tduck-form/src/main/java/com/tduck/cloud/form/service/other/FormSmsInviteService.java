package com.tduck.cloud.form.service.other;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.sms.SmsConfiguration;
import com.tduck.cloud.common.sms.SmsService;
import com.tduck.cloud.common.sms.YunxinSmsServiceImpl;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.entity.enums.FormStatusEnum;
import com.tduck.cloud.form.request.FormSmsInviteRequest;
import com.tduck.cloud.form.service.UserFormService;
import com.tduck.cloud.form.util.FormAuthUtils;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.util.LinkedHashMap;
import java.util.Map;

/** 复用表单权限与现有短信发送器，不向问卷用户暴露云信凭据。 */
@Service
@RequiredArgsConstructor
public class FormSmsInviteService {

    private final UserFormService userFormService;
    private final com.tduck.cloud.form.survey.SurveyLegacyInviteService legacyInvites;

    public Map<String, Object> getConfig(String formKey) {
        checkForm(formKey, false);
        SmsService sender = SmsConfiguration.getSmsService();
        Map<String, Object> config = new LinkedHashMap<>();
        config.put(
                "enabled",
                sender instanceof YunxinSmsServiceImpl && sender.properties.isSurveyEnabled());
        config.put(
                "templateId",
                sender instanceof YunxinSmsServiceImpl
                        ? sender.properties.getYunxinInvitationTemplateId()
                        : null);
        return config;
    }

    public Map<String, Object> send(FormSmsInviteRequest request) {
        checkForm(request.getFormKey(), true);
        SmsService sender = SmsConfiguration.getSmsService();
        if (!(sender instanceof YunxinSmsServiceImpl)) {
            throw new BaseException("请联系管理员在系统配置中启用移动云云信");
        }
        return legacyInvites.create(request);
    }

    private void checkForm(String formKey, boolean requireReleased) {
        FormAuthUtils.hasPermission(formKey);
        UserFormEntity form = userFormService.getByKey(formKey);
        if (form == null
                || Boolean.TRUE.equals(form.getDeleted())
                || Boolean.TRUE.equals(form.getFolder())) {
            throw new BaseException("问卷不存在或已删除");
        }
        if (requireReleased && form.getStatus() != FormStatusEnum.RELEASE) {
            throw new BaseException("请先发布问卷后再发送短信邀请");
        }
    }
}
