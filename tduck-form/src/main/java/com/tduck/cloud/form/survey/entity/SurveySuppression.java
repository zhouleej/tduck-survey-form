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
@TableName("fm_survey_suppression")
public class SurveySuppression extends BaseEntity<SurveySuppression> {
    private String accountKey;
    private String phone;
    private Long actorId;
    private String reason;
    private Boolean active;
}
