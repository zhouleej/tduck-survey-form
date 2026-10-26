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
@TableName("fm_survey_answer_source")
public class SurveyAnswerSource extends BaseEntity<SurveyAnswerSource> {
    private String formKey;
    private Long answerId;
    private Long batchId;
    private Long invitationId;
    private Long recipientId;
    private String mode;
}
