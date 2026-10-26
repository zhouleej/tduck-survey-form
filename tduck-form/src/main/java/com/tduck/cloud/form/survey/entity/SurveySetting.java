package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tduck.cloud.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_setting")
public class SurveySetting extends BaseEntity<SurveySetting> {
    private String formKey;
    private String mode;
    private Boolean modeLocked;
    private String defaultTemplateId;
}
