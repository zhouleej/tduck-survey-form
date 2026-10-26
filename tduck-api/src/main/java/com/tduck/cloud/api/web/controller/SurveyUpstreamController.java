package com.tduck.cloud.api.web.controller;

import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.survey.SurveyUpstreamService;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
public class SurveyUpstreamController {
    private final SurveyUpstreamService replies;

    @GetMapping("/user/form/sms/replies")
    public Result<?> list(
            String formKey,
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            String keyword,
            String state) {
        return Result.success(replies.list(formKey, page, size, keyword, state));
    }

    @GetMapping("/mange/sms/replies/{id}/candidates")
    public Result<?> candidates(@PathVariable Long id) {
        return Result.success(replies.candidates(id));
    }

    @PostMapping("/mange/sms/replies/{id}/assign")
    public Result<?> assign(@PathVariable Long id, @RequestBody ReplyRequest request) {
        replies.assign(id, request.formKey);
        return Result.success();
    }

    @PostMapping("/user/form/sms/replies/{id}/process")
    public Result<?> process(@PathVariable Long id, @RequestBody ReplyRequest request) {
        replies.process(id, request.state, request.note);
        return Result.success();
    }

    @PostMapping("/mange/sms/replies/sync")
    public Result<?> sync() {
        replies.requestSync();
        return Result.success();
    }

    @GetMapping("/mange/sms/sync-health")
    public Result<?> health() {
        return Result.success(replies.health());
    }

    @GetMapping("/mange/sms/suppressions")
    public Result<?> suppressions(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size,
            String phone) {
        return Result.success(replies.suppressions(page, size, phone));
    }

    @PostMapping("/mange/sms/suppressions")
    public Result<?> suppress(@RequestBody ReplyRequest request) {
        replies.suppress(request.phone, request.note, request.active);
        return Result.success();
    }

    @Data
    public static class ReplyRequest {
        private String formKey;
        private String state;
        private String note;
        private String phone;
        private boolean active;
    }
}
