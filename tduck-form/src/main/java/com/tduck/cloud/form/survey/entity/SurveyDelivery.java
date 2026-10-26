package com.tduck.cloud.form.survey.entity;
import com.baomidou.mybatisplus.annotation.TableName;
import com.tduck.cloud.common.entity.BaseEntity;
import lombok.Data;
import lombok.EqualsAndHashCode;
import java.time.LocalDateTime;
import com.fasterxml.jackson.annotation.JsonIgnore;
import com.fasterxml.jackson.annotation.JsonFormat;
@Data
@EqualsAndHashCode(callSuper=true)
@TableName("fm_survey_delivery")
public class SurveyDelivery extends BaseEntity<SurveyDelivery> {
    private String formKey;
    private Long batchId;
    private Long recipientId;
    private Long attemptId;
    private Long invitationId;
    private String phone;
    private String name;
    private String company;
    private String groupName;
    @JsonIgnore
    private String variablesJson;
    private String renderedContent;
    private String submitStatus;
    private String sendStatus;
    private String deliveryStatus;
    private String reason;
    private String providerRecordId;
    private String providerTaskId;
    private String providerMsgId;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime submittedAt;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime sendTime;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime recvTime;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime syncedAt;
}
