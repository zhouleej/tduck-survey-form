package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.tduck.cloud.form.survey.entity.SurveySetting;
import com.tduck.cloud.form.survey.mapper.SurveySettingMapper;

import lombok.RequiredArgsConstructor;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@RequiredArgsConstructor
public class SurveySettingsService {
    private final SurveySettingMapper mapper;
    private final SurveyAccess access;

    public SurveySetting get(String formKey) {
        access.requireForm(formKey);
        return ensure(formKey);
    }

    public SurveySetting ensure(String formKey) {
        SurveySetting setting =
                mapper.selectOne(new QueryWrapper<SurveySetting>().eq("form_key", formKey));
        if (setting != null) return setting;
        setting = new SurveySetting();
        setting.setFormKey(formKey);
        setting.setMode("ANONYMOUS");
        setting.setModeLocked(false);
        try {
            mapper.insert(setting);
        } catch (DuplicateKeyException e) {
            return mapper.selectOne(new QueryWrapper<SurveySetting>().eq("form_key", formKey));
        }
        return setting;
    }

    @Transactional(rollbackFor = Exception.class)
    public SurveySetting save(SurveySetting request) {
        access.requireForm(request.getFormKey());
        check(
                "ANONYMOUS".equals(request.getMode()) || "TRACKED".equals(request.getMode()),
                "填写方式无效");
        ensure(request.getFormKey());
        SurveySetting current =
                mapper.selectOne(
                        new QueryWrapper<SurveySetting>()
                                .eq("form_key", request.getFormKey())
                                .last("FOR UPDATE"));
        check(
                !Boolean.TRUE.equals(current.getModeLocked())
                        || current.getMode().equals(request.getMode()),
                "已有正式邀请，填写方式已锁定；请复制问卷开展新调研");
        current.setMode(request.getMode());
        current.setDefaultTemplateId(request.getDefaultTemplateId());
        mapper.updateById(current);
        access.audit(current.getFormKey(), "SETTINGS", current.getId(), "更新调研设置");
        return current;
    }
}
