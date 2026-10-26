package com.tduck.cloud.api.web.controller;

import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.survey.SurveyReportService;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

import java.io.IOException;

import javax.servlet.http.HttpServletResponse;

@RestController
@RequiredArgsConstructor
public class SurveyReportController {
    private final SurveyReportService reports;

    @GetMapping("/user/form/survey/report")
    public Result<?> report(
            @RequestParam String formKey, Long batchId, String groupName, String company) {
        return Result.success(reports.report(formKey, batchId, groupName, company));
    }

    @GetMapping("/user/form/survey/incomplete/export")
    public void incomplete(
            @RequestParam String formKey,
            Long batchId,
            String groupName,
            String company,
            HttpServletResponse response)
            throws IOException {
        reports.exportIncomplete(formKey, batchId, groupName, company, response);
    }

    @GetMapping("/user/form/sms/batches/{id}/export")
    public void export(
            @PathVariable Long id,
            String keyword,
            String submitStatus,
            String sendStatus,
            String deliveryStatus,
            String participation,
            HttpServletResponse response)
            throws IOException {
        reports.exportDeliveries(
                id, keyword, submitStatus, sendStatus, deliveryStatus, participation, response);
    }

    @GetMapping("/user/form/sms/replies/export")
    public void replies(String formKey, HttpServletResponse response) throws IOException {
        reports.exportReplies(formKey, response);
    }
}
