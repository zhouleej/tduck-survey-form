package com.tduck.cloud.api.web.controller;

import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.common.validator.ValidatorUtils;
import com.tduck.cloud.form.request.FormSmsInviteRequest;
import com.tduck.cloud.form.service.other.FormSmsInviteService;
import lombok.RequiredArgsConstructor;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import javax.validation.constraints.NotBlank;

/** 问卷发布页的短信邀请。由既有登录拦截器及表单权限检查保护。 */
@RestController
@Validated
@RequiredArgsConstructor
@RequestMapping("/user/form/sms")
public class UserFormSmsController {

    private final FormSmsInviteService formSmsInviteService;

    @GetMapping("/config")
    public Result getConfig(@RequestParam @NotBlank String formKey) {
        return Result.success(formSmsInviteService.getConfig(formKey));
    }

    @PostMapping("/invite")
    public Result invite(@RequestBody FormSmsInviteRequest request) {
        ValidatorUtils.validateEntity(request);
        return Result.success(formSmsInviteService.send(request));
    }
}
