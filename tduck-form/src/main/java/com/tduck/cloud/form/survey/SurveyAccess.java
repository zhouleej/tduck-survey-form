package com.tduck.cloud.form.survey;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.SecurityUtils;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.entity.enums.FormStatusEnum;
import com.tduck.cloud.form.service.UserFormService;
import com.tduck.cloud.form.survey.entity.SurveyAudit;
import com.tduck.cloud.form.survey.mapper.SurveyAuditMapper;
import com.tduck.cloud.form.util.FormAuthUtils;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Component;

@Component
@RequiredArgsConstructor
public class SurveyAccess {
    private final UserFormService forms;
    private final SurveyAuditMapper audits;

    public UserFormEntity requireForm(String formKey) {
        UserFormEntity form = existingForm(formKey);
        FormAuthUtils.hasPermission(formKey);
        return form;
    }

    public UserFormEntity existingForm(String formKey) {
        check(formKey != null && !formKey.trim().isEmpty(), "请选择问卷");
        UserFormEntity form = forms.getByKey(formKey);
        check(
                form != null
                        && !Boolean.TRUE.equals(form.getDeleted())
                        && !Boolean.TRUE.equals(form.getFolder()),
                "问卷不存在或已删除");
        return form;
    }

    public UserFormEntity requireReleased(String formKey) {
        UserFormEntity form = requireForm(formKey);
        check(form.getStatus() == FormStatusEnum.RELEASE, "请先发布问卷");
        return form;
    }

    public void admin() {
        check(SecurityUtils.isAdmin(), "仅管理员可执行此操作");
    }

    public void audit(String formKey, String action, Object objectId, String detail) {
        SurveyAudit row = new SurveyAudit();
        row.setFormKey(formKey);
        try {
            row.setActorId(SecurityUtils.getUserId());
        } catch (Exception ignored) {
        }
        row.setAction(action);
        row.setObjectId(objectId == null ? null : objectId.toString());
        row.setDetail(detail);
        audits.insert(row);
    }

    public static void check(boolean valid, String message) {
        if (!valid) throw new BaseException(message);
    }
}
