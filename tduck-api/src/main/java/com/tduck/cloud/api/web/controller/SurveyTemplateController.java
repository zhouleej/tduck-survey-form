package com.tduck.cloud.api.web.controller;

import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.survey.*;
import com.tduck.cloud.form.survey.entity.SurveySmsTemplate;

import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/user/form/sms")
@RequiredArgsConstructor
public class SurveyTemplateController {
    private final SurveyTemplateService templates;
    private final SurveyGateway gateway;

    @GetMapping("/capabilities")
    public Result<?> capabilities() {
        return Result.success(gateway.capabilities());
    }

    @GetMapping("/templates")
    public Result<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(templates.list(page, size));
    }

    @PostMapping("/templates")
    public Result<?> save(@RequestBody SurveySmsTemplate template) {
        return Result.success(templates.save(template));
    }

    @PostMapping("/templates/{id}/apply")
    public Result<?> apply(@PathVariable Long id) {
        return Result.success(templates.apply(id));
    }

    @PostMapping("/templates/{id}/audit")
    public Result<?> audit(@PathVariable Long id) {
        return Result.success(templates.refreshAudit(id));
    }
}
