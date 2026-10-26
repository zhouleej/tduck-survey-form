package com.tduck.cloud.api.web.controller;
import com.tduck.cloud.common.util.Result;
import com.tduck.cloud.form.survey.SurveyInvitationService;
import lombok.Data;
import lombok.RequiredArgsConstructor;
import org.springframework.web.bind.annotation.*;
import javax.annotation.security.PermitAll;

@RestController
@RequestMapping("/public/form/invitation")
@RequiredArgsConstructor
public class SurveyInvitationController {
    private final SurveyInvitationService invitations;
    @PermitAll @GetMapping("/{token}")
    public Result<?> resolve(@PathVariable String token) { return Result.success(invitations.publicContext(token)); }
    @PermitAll @PostMapping("/visit")
    public Result<?> visit(@RequestBody TokenRequest request) { invitations.visit(request.token); return Result.success(); }
    @Data public static class TokenRequest { private String token; }
}
