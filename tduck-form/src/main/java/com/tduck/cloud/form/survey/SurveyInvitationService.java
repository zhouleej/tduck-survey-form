package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.baomidou.mybatisplus.core.conditions.update.UpdateWrapper;
import com.tduck.cloud.form.entity.UserFormDataEntity;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.entity.enums.FormStatusEnum;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import lombok.RequiredArgsConstructor;

import org.springframework.stereotype.Service;

import java.security.SecureRandom;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.util.*;

@Service
@RequiredArgsConstructor
public class SurveyInvitationService {
    private final SurveyInvitationMapper invitations;
    private final SurveyAnswerSourceMapper sources;
    private final SurveyAccess access;
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final char[] ALPHABET =
            "0123456789abcdefghijklmnopqrstuvwxyzABCDEFGHIJKLMNOPQRSTUVWXYZ".toCharArray();

    public static String token() {
        char[] value = new char[20];
        for (int i = 0; i < value.length; i++) value[i] = ALPHABET[RANDOM.nextInt(ALPHABET.length)];
        return new String(value);
    }

    /** 在创建批次的事务中调用。匿名批次只创建一条无联系人字段的共同邀请。 */
    public SurveyInvitation create(String formKey, Long batchId, Long recipientId, String mode) {
        check("TRACKED".equals(mode) || "ANONYMOUS".equals(mode), "邀请方式无效");
        check(!"TRACKED".equals(mode) || recipientId != null, "跟踪邀请缺少对象");
        SurveyInvitation row = new SurveyInvitation();
        row.setFormKey(formKey);
        row.setBatchId(batchId);
        row.setRecipientId("TRACKED".equals(mode) ? recipientId : null);
        row.setMode(mode);
        row.setToken(token());
        row.setActive(true);
        row.setVisitCount(0L);
        invitations.insert(row);
        return row;
    }

    public SurveyInvitation resolve(String token) {
        check(token != null && token.matches("[A-Za-z0-9]{20}"), "邀请链接无效");
        SurveyInvitation invitation =
                invitations.selectOne(
                        new QueryWrapper<SurveyInvitation>().eq("token", token).eq("active", true));
        check(invitation != null, "邀请链接不存在或已停用");
        UserFormEntity form = access.existingForm(invitation.getFormKey());
        check(form.getStatus() == FormStatusEnum.RELEASE, "问卷尚未发布或已停止收集");
        return invitation;
    }

    public Map<String, Object> publicContext(String token) {
        SurveyInvitation row = resolve(token);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("formKey", row.getFormKey());
        result.put("mode", row.getMode());
        result.put(
                "notice",
                "TRACKED".equals(row.getMode())
                        ? "本问卷会将通过此邀请链接提交的答卷关联至邀请对象；链接转发不代表身份认证。"
                        : "本批次采用匿名调研，答卷不会关联至短信收件人。");
        return result;
    }

    public void visit(String token) {
        SurveyInvitation row = resolve(token);
        LocalDateTime now = LocalDateTime.now(ZoneId.of("Asia/Shanghai"));
        invitations.update(
                null,
                new UpdateWrapper<SurveyInvitation>()
                        .eq("id", row.getId())
                        .setSql("visit_count = visit_count + 1")
                        .setSql("first_visit_at = COALESCE(first_visit_at, CURRENT_TIMESTAMP)")
                        .set("last_visit_at", now));
    }

    /** 与答卷保存处于同一事务；不可信的客户端ID一律不用作归属。 */
    public void recordAnswer(UserFormDataEntity answer) {
        if (answer.getInvitationToken() == null || answer.getInvitationToken().trim().isEmpty())
            return;
        SurveyInvitation invitation = resolve(answer.getInvitationToken());
        check(invitation.getFormKey().equals(answer.getFormKey()), "邀请链接与问卷不匹配");
        SurveyAnswerSource source = new SurveyAnswerSource();
        source.setFormKey(answer.getFormKey());
        source.setAnswerId(answer.getId());
        source.setBatchId(invitation.getBatchId());
        source.setMode(invitation.getMode());
        if ("TRACKED".equals(invitation.getMode())) {
            source.setInvitationId(invitation.getId());
            source.setRecipientId(invitation.getRecipientId());
        }
        sources.insert(source);
    }
}
