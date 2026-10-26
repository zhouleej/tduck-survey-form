package com.tduck.cloud.form.survey;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.entity.*;
import com.tduck.cloud.form.entity.enums.FormStatusEnum;
import com.tduck.cloud.form.survey.entity.*;
import com.tduck.cloud.form.survey.mapper.*;

import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.*;

public class SurveyInvitationServiceTest {
    @Test
    public void invitationTokenIsInputOnlyAndNeverIncludedInExportsOrWebhooks() {
        UserFormDataEntity answer =
                com.tduck.cloud.common.util.JsonUtils.jsonToObj(
                        "{\"formKey\":\"form\",\"invitationToken\":\"01234567890123456789\"}",
                        UserFormDataEntity.class);
        assertEquals("01234567890123456789", answer.getInvitationToken());
        answer.setId(Long.MAX_VALUE);
        assertTrue(cn.hutool.json.JSONUtil.parseObj(com.tduck.cloud.common.util.JsonUtils.objToJson(answer)).get("id") instanceof String);
        assertFalse(
                com.tduck.cloud.common.util.JsonUtils.objToJson(answer)
                        .contains("invitationToken"));
        answer.setOriginalData(new java.util.HashMap<>());
        answer.setCreateTime(java.time.LocalDateTime.now());
        answer.setUpdateTime(java.time.LocalDateTime.now());
        assertFalse(
                com.tduck.cloud.form.service.data.FormDataBaseService.convertDocument(answer)
                        .containsKey("invitationToken"));
    }

    @Test
    public void anonymousAnswerNeverLinksRecipientOrInvitation() {
        SurveyInvitationMapper invites = mock(SurveyInvitationMapper.class);
        SurveyAnswerSourceMapper sources = mock(SurveyAnswerSourceMapper.class);
        SurveyAccess access = mock(SurveyAccess.class);
        SurveyInvitationService service = new SurveyInvitationService(invites, sources, access);
        SurveyInvitation invitation = new SurveyInvitation();
        invitation.setId(1L);
        invitation.setMode("ANONYMOUS");
        invitation.setFormKey("form");
        invitation.setBatchId(3L);
        invitation.setRecipientId(99L);
        when(invites.selectOne(any())).thenReturn(invitation);
        UserFormEntity form = new UserFormEntity();
        form.setStatus(FormStatusEnum.RELEASE);
        when(access.existingForm("form")).thenReturn(form);
        UserFormDataEntity answer = new UserFormDataEntity();
        answer.setFormKey("form");
        answer.setId(4L);
        answer.setInvitationToken(SurveyInvitationService.token());
        service.recordAnswer(answer);
        ArgumentCaptor<SurveyAnswerSource> captor =
                ArgumentCaptor.forClass(SurveyAnswerSource.class);
        verify(sources).insert(captor.capture());
        assertEquals(Long.valueOf(3L), captor.getValue().getBatchId());
        assertNull(captor.getValue().getRecipientId());
        assertNull(captor.getValue().getInvitationId());
        answer.setFormKey("other");
        assertThrows(BaseException.class, () -> service.recordAnswer(answer));
    }

    @Test
    public void secureTokensFitOneVariableAndAreDistinct() {
        Set<String> seen = new HashSet<>();
        for (int i = 0; i < 10000; i++) {
            String token = SurveyInvitationService.token();
            assertTrue(token.matches("[A-Za-z0-9]{20}"));
            assertTrue(seen.add(token));
        }
    }

    @Test
    public void legacyAnswersDoNotCreateAttribution() {
        SurveyAnswerSourceMapper sources = mock(SurveyAnswerSourceMapper.class);
        new SurveyInvitationService(
                        mock(SurveyInvitationMapper.class), sources, mock(SurveyAccess.class))
                .recordAnswer(new UserFormDataEntity());
        verifyNoInteractions(sources);
    }
}
