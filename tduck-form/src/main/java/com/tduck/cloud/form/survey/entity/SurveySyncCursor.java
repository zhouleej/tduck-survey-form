package com.tduck.cloud.form.survey.entity;

import com.baomidou.mybatisplus.annotation.TableName;
import com.fasterxml.jackson.annotation.JsonFormat;
import com.tduck.cloud.common.entity.BaseEntity;

import lombok.Data;
import lombok.EqualsAndHashCode;

import java.time.LocalDateTime;

@Data
@EqualsAndHashCode(callSuper = true)
@TableName("fm_survey_sync_cursor")
public class SurveySyncCursor extends BaseEntity<SurveySyncCursor> {
    private String previousPageHash;
    private Boolean manualRequested;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime requestedFrom;

    private String accountKey;
    private String kind;
    private String sourceKey;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime windowStart;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime windowEnd;

    private Integer pageNum;
    private Integer pollStep;
    private Integer failures;

    @JsonFormat(pattern = "yyyy-MM-dd HH:mm:ss")
    private LocalDateTime nextPollAt;

    private String lastError;
}
