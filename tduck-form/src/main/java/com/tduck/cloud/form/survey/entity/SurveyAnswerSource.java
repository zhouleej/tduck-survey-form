package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tduck.cloud.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_answer_source")
public class SurveyAnswerSource extends BaseEntity<SurveyAnswerSource> {
    private String formKey;
    private Long answerId;
    private Long batchId;
    private Long invitationId;
    private Long recipientId;
    private String mode;
}
