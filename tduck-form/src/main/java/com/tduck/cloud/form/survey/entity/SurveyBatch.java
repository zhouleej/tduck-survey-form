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
@TableName("fm_survey_batch")
public class SurveyBatch extends BaseEntity<SurveyBatch> {
    private String formKey;
    private String name;
    private String kind;
    private String mode;
    private Long templateId;
    private String templateVersion;
    @JsonIgnore
    private String templateSnapshot;
    private String accountKey;
    @JsonIgnore
    private String configVersion;
    private String status;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scheduledAt;
    private Long ownerId;
    private String requestKey;
    @JsonIgnore
    private String requestHash;
    private Integer recipientCount;
    private Integer excludedCount;
    private Long sourceBatchId;
    private Boolean coverageAccepted;
    private String reason;
}
