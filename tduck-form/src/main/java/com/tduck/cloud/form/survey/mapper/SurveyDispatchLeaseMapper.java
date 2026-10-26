package com.tduck.cloud.form.survey.mapper;
import org.apache.ibatis.annotations.*;
import java.time.LocalDateTime;

public interface SurveyDispatchLeaseMapper {
    @Insert("INSERT IGNORE INTO fm_survey_dispatch_lease(account_key) VALUES(#{key})")
    void ensure(@Param("key") String key);
    @Update("UPDATE fm_survey_dispatch_lease SET lease_owner=#{owner},lease_until=#{until} WHERE account_key=#{key} AND (lease_until IS NULL OR lease_until < #{now})")
    int claim(@Param("key") String key,@Param("owner") String owner,@Param("now") LocalDateTime now,@Param("until") LocalDateTime until);
    @Update("UPDATE fm_survey_dispatch_lease SET lease_until=#{until} WHERE account_key=#{key} AND lease_owner=#{owner}")
    int renew(@Param("key") String key,@Param("owner") String owner,@Param("until") LocalDateTime until);
    @Update("UPDATE fm_survey_dispatch_lease SET lease_until=NULL,lease_owner=NULL WHERE account_key=#{key} AND lease_owner=#{owner}")
    int release(@Param("key") String key,@Param("owner") String owner);
    @Select("SELECT next_request_at FROM fm_survey_dispatch_lease WHERE account_key=#{key}")
    LocalDateTime nextRequest(@Param("key") String key);
    @Update("UPDATE fm_survey_dispatch_lease SET next_request_at=#{next} WHERE account_key=#{key} AND lease_owner=#{owner}")
    int nextRequest(@Param("key") String key,@Param("owner") String owner,@Param("next") LocalDateTime next);
}
