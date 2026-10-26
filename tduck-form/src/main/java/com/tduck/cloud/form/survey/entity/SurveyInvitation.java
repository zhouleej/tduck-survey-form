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
@TableName("fm_survey_invitation")
public class SurveyInvitation extends BaseEntity<SurveyInvitation> {
    private String formKey;
    private Long batchId;
    private Long recipientId;
    private String mode;
    @JsonIgnore
    private String token;
    private Boolean active;
    private Long visitCount;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime firstVisitAt;
    @JsonFormat(pattern="yyyy-MM-dd HH:mm:ss")
    private LocalDateTime lastVisitAt;
}
