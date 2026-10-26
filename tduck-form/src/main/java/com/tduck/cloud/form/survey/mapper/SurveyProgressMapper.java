package com.tduck.cloud.form.survey.mapper;

import org.apache.ibatis.annotations.*;

import java.util.List;
import java.util.Map;

public interface SurveyProgressMapper {
    @Select(
            "SELECT s.* FROM fm_survey_answer_source s JOIN fm_user_form_data d ON d.id=s.answer_id"
                    + " AND d.form_key=s.form_key WHERE s.form_key=#{formKey}")
    List<com.tduck.cloud.form.survey.entity.SurveyAnswerSource> sources(
            @Param("formKey") String formKey);

    @Select(
            "SELECT DISTINCT s.recipient_id FROM fm_survey_answer_source s JOIN fm_user_form_data d"
                + " ON d.id=s.answer_id AND d.form_key=s.form_key WHERE s.form_key=#{formKey} AND"
                + " s.mode='TRACKED' AND s.recipient_id IS NOT NULL")
    List<Long> completed(@Param("formKey") String formKey);

    @Select(
            "SELECT DISTINCT recipient_id FROM fm_survey_invitation WHERE form_key=#{formKey} AND"
                    + " mode='TRACKED' AND visit_count>0 AND recipient_id IS NOT NULL")
    List<Long> visited(@Param("formKey") String formKey);

    @Select(
            "SELECT d.id,d.create_time FROM fm_survey_answer_source s JOIN fm_user_form_data d ON"
                    + " d.id=s.answer_id AND d.form_key=s.form_key WHERE s.form_key=#{formKey} AND"
                    + " s.recipient_id=#{recipientId} AND s.mode='TRACKED' ORDER BY d.id DESC")
    List<Map<String, Object>> answers(
            @Param("formKey") String formKey, @Param("recipientId") Long recipientId);
}
