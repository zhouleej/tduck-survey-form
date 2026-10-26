package com.tduck.cloud.form.survey;

import com.fasterxml.jackson.annotation.JsonFormat;

import lombok.Data;

import java.time.LocalDateTime;
import java.util.List;

@Data
public class SurveyBatchRequest {
    private String formKey;
    private String name;
    private Long templateId;
    private List<Long> recipientIds;
    private String groupName;
    private String company;
    private String kind = "INVITE";
    private Long sourceBatchId;
    private String participation;
    private String requestKey;
    private String expectedTemplateVersion;
    private String expectedSelectionHash;
    @com.fasterxml.jackson.annotation.JsonIgnore private String legacyIntentHash;
    private boolean coverageAccepted;
    private boolean untrackedAccepted;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime scheduledAt;
}
