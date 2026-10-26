package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.tduck.cloud.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_upstream")
public class SurveyUpstream extends BaseEntity<SurveyUpstream> {
    private String accountKey;
    private String providerRecordId;
    private String fingerprint;
    private String phone;
    private String smsCode;
    private String content;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime recvTime;

    private String assignedFormKey;
    private Long recipientId;
    private String state;
    private String note;
    private Long assignedBy;
}
