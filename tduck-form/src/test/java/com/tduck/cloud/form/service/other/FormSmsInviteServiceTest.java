package com.tduck.cloud.form.service.other;

import cn.hutool.json.JSONUtil;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.sms.SmsConfiguration;
import com.tduck.cloud.common.sms.SmsPlatformProperties;
import com.tduck.cloud.common.sms.YunxinSmsServiceImpl;
import com.tduck.cloud.common.util.SpringContextUtils;
import com.tduck.cloud.envconfig.service.SysEnvConfigService;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.entity.enums.FormStatusEnum;
import com.tduck.cloud.form.request.FormSmsInviteRequest;
import com.tduck.cloud.form.service.UserFormService;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.springframework.context.support.GenericApplicationContext;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.web.context.request.RequestContextHolder;
import org.springframework.web.context.request.ServletRequestAttributes;

import javax.validation.Validation;
import javax.validation.ValidatorFactory;
import java.util.Arrays;
import java.util.Collections;
import java.util.Map;

import static org.junit.Assert.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

public class FormSmsInviteServiceTest {
    private GenericApplicationContext context;
    private UserFormService forms;
    private UserFormEntity form;
    private FormSmsInviteService service;
    private FormSmsInviteRequest request;
    private YunxinSmsServiceImpl sender;

    @Before
    public void setUp() {
        forms = mock(UserFormService.class);
        context = new GenericApplicationContext();
        context.getBeanFactory().registerSingleton("userFormService", forms);
        context.getBeanFactory().registerSingleton("sysEnvConfigService", mock(SysEnvConfigService.class));
        context.refresh();
        SpringContextUtils spring = new SpringContextUtils();
        spring.setApplicationContext(context);
        spring.postProcessBeanFactory(context.getBeanFactory());
        MockHttpServletRequest http = new MockHttpServletRequest();
        http.setAttribute("userId", 2L);
        RequestContextHolder.setRequestAttributes(new ServletRequestAttributes(http));
        form = new UserFormEntity();
        form.setFormKey("AbCd1234");
        form.setUserId(2L);
        form.setStatus(FormStatusEnum.RELEASE);
        form.setDeleted(false);
        form.setFolder(false);
        when(forms.getByKey("AbCd1234")).thenReturn(form);
        service = new FormSmsInviteService(forms);
        sender = mock(YunxinSmsServiceImpl.class);
        sender.properties = new SmsPlatformProperties();
        sender.properties.setYunxinInvitationTemplateId("invite-template");
        sender.properties.setYunxinUserId("do-not-expose");
        ReflectionTestUtils.setField(SmsConfiguration.class, "smsService", sender);
        request = new FormSmsInviteRequest();
        request.setFormKey("AbCd1234");
        request.setTemplateId("invite-template");
        request.setPhoneNumbers(Arrays.asList("13800138000", "13800138000"));
        request.setTemplateParams(Collections.singletonMap("p1", "AbCd1234"));
    }

    @After
    public void tearDown() {
        ReflectionTestUtils.setField(SmsConfiguration.class, "smsService", null);
        RequestContextHolder.resetRequestAttributes();
        context.close();
    }

    @Test
    public void returnsOnlySafeConfiguration() {
        Map<String, Object> config = service.getConfig("AbCd1234");
        assertEquals(2, config.size());
        assertEquals(true, config.get("enabled"));
        assertEquals("invite-template", config.get("templateId"));
        assertFalse(config.toString().contains("do-not-expose"));
    }

    @Test
    public void reportsAcceptanceWithTaskIdentifiersAndDistinctCount() {
        when(sender.sendBatchSms(anyList(), anyString(), anyMap())).thenReturn(JSONUtil.parseObj("{\"taskid\":\"12\",\"msgid\":\"34\"}"));
        Map<String, Object> result = service.send(request);
        assertEquals("12", result.get("taskId"));
        assertEquals("34", result.get("msgId"));
        assertEquals(1, result.get("recipientCount"));
        verify(sender, times(1)).sendBatchSms(request.getPhoneNumbers(), "invite-template", request.getTemplateParams());
    }

    @Test
    public void rejectsOtherUsersBeforeSending() {
        form.setUserId(3L);
        assertThrows(BaseException.class, () -> service.send(request));
        assertThrows(BaseException.class, () -> service.getConfig("AbCd1234"));
        verifyNoInteractions(sender);
    }

    @Test
    public void rejectsUnpublishedDeletedMissingAndFolderForms() {
        form.setStatus(FormStatusEnum.STOP);
        assertThrows(BaseException.class, () -> service.send(request));
        form.setStatus(FormStatusEnum.RELEASE);
        form.setDeleted(true);
        assertThrows(BaseException.class, () -> service.send(request));
        form.setDeleted(false);
        form.setFolder(true);
        assertThrows(BaseException.class, () -> service.send(request));
        when(forms.getByKey("AbCd1234")).thenReturn(null);
        assertThrows(BaseException.class, () -> service.send(request));
        verifyNoInteractions(sender);
    }

    @Test
    public void unavailableChannelDoesNotSend() {
        ReflectionTestUtils.setField(SmsConfiguration.class, "smsService", null);
        assertEquals(false, service.getConfig("AbCd1234").get("enabled"));
        assertThrows(BaseException.class, () -> service.send(request));
        verifyNoInteractions(sender);
    }

    @Test
    public void validatesRecipientElementsAndBatchLimit() {
        try (ValidatorFactory factory = Validation.buildDefaultValidatorFactory()) {
            assertTrue(factory.getValidator().validate(request).isEmpty());
            request.setPhoneNumbers(Collections.singletonList("invalid"));
            assertFalse(factory.getValidator().validate(request).isEmpty());
            request.setPhoneNumbers(Collections.nCopies(101, "13800138000"));
            assertFalse(factory.getValidator().validate(request).isEmpty());
        }
    }
}
