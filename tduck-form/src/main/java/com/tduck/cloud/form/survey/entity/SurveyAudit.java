package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tduck.cloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_audit")
public class SurveyAudit extends BaseEntity<SurveyAudit> {
    private String formKey;
    private Long actorId;
    private String action;
    private String objectId;
    private String detail;
}
