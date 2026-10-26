package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tduck.cloud.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_suppression")
public class SurveySuppression extends BaseEntity<SurveySuppression> {
    private String accountKey;
    private String phone;
    private Long actorId;
    private String reason;
    private Boolean active;
}
