package com.tduck.cloud.api.web.controller;

import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.survey.SurveyRecipientService;
import com.tduck.cloud.form.survey.SurveySettingsService;
import com.tduck.cloud.form.survey.entity.SurveyRecipient;
import com.tduck.cloud.form.survey.entity.SurveySetting;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;
import org.springframework.web.multipart.MultipartFile;

import java.io.IOException;
import java.util.List;
import java.util.Map;

import javax.servlet.http.HttpServletResponse;

@RestController
@RequestMapping("/user/form/survey")
@RequiredArgsConstructor
public class SurveyRecipientController {
    private final SurveyRecipientService recipients;
    private final SurveySettingsService settings;
    private final com.tduck.cloud.form.survey.SurveyBatchService batches;

    @GetMapping("/settings")
    public Result<?> settings(@RequestParam String formKey) {
        return Result.success(settings.get(formKey));
    }

    @PostMapping("/settings")
    public Result<?> settings(@RequestBody SurveySetting request) {
        return Result.success(settings.save(request));
    }

    @GetMapping("/recipients")
    public Result<?> list(
            @RequestParam String formKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            String keyword,
            String groupName,
            String company,
            Boolean disabled) {
        return Result.success(
                recipients.page(formKey, page, size, keyword, groupName, company, disabled));
    }

    @PostMapping("/recipients")
    public Result<?> save(@RequestBody SurveyRecipient request) {
        return Result.success(recipients.saveRecipient(request));
    }

    @PostMapping("/recipients/progress")
    public Result<?> progress(@RequestBody ProgressRequest request) {
        return Result.success(batches.recipientProgress(request.formKey, request.ids));
    }

    @Data
    public static class ProgressRequest {
        private String formKey;
        private List<Long> ids;
    }

    @PostMapping("/recipients/import/preview")
    public Result<?> previewFile(@RequestParam String formKey, @RequestParam MultipartFile file)
            throws IOException {
        return Result.success(recipients.preview(formKey, recipients.readExcel(file)));
    }

    @PostMapping("/recipients/preview")
    public Result<?> preview(@RequestBody ImportRequest request) {
        return Result.success(recipients.preview(request.formKey, request.rows));
    }

    @PostMapping("/recipients/import/confirm")
    public Result<?> confirm(@RequestBody ImportRequest request) {
        return Result.success(
                recipients.confirm(request.formKey, request.rows, request.updateExisting));
    }

    @GetMapping("/recipients/export")
    public void export(
            @RequestParam String formKey,
            @RequestParam(defaultValue = "false") boolean template,
            HttpServletResponse response)
            throws IOException {
        recipients.export(formKey, response, template);
    }

    @Data
    public static class ImportRequest {
        private String formKey;
        private List<Map<String, Object>> rows;
        private boolean updateExisting;
    }
}
