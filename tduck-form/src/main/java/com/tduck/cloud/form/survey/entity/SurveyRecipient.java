package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tduck.cloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_recipient")
public class SurveyRecipient extends BaseEntity<SurveyRecipient> {
    private String formKey;
    private String phone;
    private String name;
    private String company;
    private String groupName;
    private String remark;
    private Boolean disabled;
}
