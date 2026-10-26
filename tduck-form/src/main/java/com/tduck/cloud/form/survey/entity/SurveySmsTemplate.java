package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.tduck.cloud.common.entity.BaseEntity;
import com.fasterxml.jackson.annotation.JsonFormat;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_sms_template")
public class SurveySmsTemplate extends BaseEntity<SurveySmsTemplate> {
    private Long ownerId;
    private Boolean shared;
    private String accountKey;
    private String name;
    private String providerTemplateId;
    private String content;
    private Boolean variable;
    private String mappingJson;
    private String version;
    private String applicationState;
    private String auditJson;
    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime auditedAt;
}
