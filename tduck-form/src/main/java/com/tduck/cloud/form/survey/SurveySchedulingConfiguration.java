package com.tduck.cloud.form.survey;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Bean;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.concurrent.ThreadPoolTaskScheduler;

@Configuration
@EnableScheduling
public class SurveySchedulingConfiguration {
    @Bean public ThreadPoolTaskScheduler taskScheduler(){ThreadPoolTaskScheduler scheduler=new ThreadPoolTaskScheduler();scheduler.setPoolSize(3);scheduler.setThreadNamePrefix("survey-scheduler-");scheduler.setWaitForTasksToCompleteOnShutdown(false);return scheduler;}
}
