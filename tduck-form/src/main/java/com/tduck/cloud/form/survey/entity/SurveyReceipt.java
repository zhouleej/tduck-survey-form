package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.tduck.cloud.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_receipt")
public class SurveyReceipt extends BaseEntity<SurveyReceipt> {
    private String accountKey;
    private String providerRecordId;
    private String recordIdentity;
    private String providerTaskId;
    private String phone;
    private String providerMsgId;
    private String custId;
    private String fingerprint;
    private String rawJson;
    private Long matchedDeliveryId;
    private String matchState;
    private String matchReason;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sendTime;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime recvTime;
}
