package com.tduck.cloud.api.web.controller;

import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.survey.SurveyReceiptService;

import lombok.Data;
import lombok.RequiredArgsConstructor;

import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/mange/sms/receipts")
@RequiredArgsConstructor
public class SurveyReceiptController {
    private final SurveyReceiptService service;

    @GetMapping
    public Result<?> list(
            @RequestParam(defaultValue = "1") int page,
            @RequestParam(defaultValue = "20") int size) {
        return Result.success(service.unmatched(page, size));
    }

    @PostMapping("/{id}/assign")
    public Result<?> assign(@PathVariable Long id, @RequestBody Assignment request) {
        service.assign(id, request.deliveryId, request.note);
        return Result.success();
    }

    @Data
    public static class Assignment {
        private Long deliveryId;
        private String note;
    }
}
