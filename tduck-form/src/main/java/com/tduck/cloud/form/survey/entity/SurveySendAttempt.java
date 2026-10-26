package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.tduck.cloud.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_send_attempt")
public class SurveySendAttempt extends BaseEntity<SurveySendAttempt> {
    private String formKey;
    private Long batchId;
    private String accountKey;
    private String state;
    private String providerTaskId;
    private String providerMsgId;
    private String providerCode;
    private String reason;
    @JsonIgnore private String requestDigest;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime startedAt;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime finishedAt;
}
