package com.tduck.cloud.form.survey;

import com.baomidou.mybatisplus.core.conditions.Wrapper;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.survey.entity.SurveyRecipient;
import com.tduck.cloud.form.survey.mapper.SurveyRecipientMapper;
import org.junit.Test;
import org.springframework.test.util.ReflectionTestUtils;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.any;

public class SurveyRecipientServiceTest {
    @Test
    @SuppressWarnings("unchecked")
    public void previewSeparatesInvalidDuplicateAndExistingWithoutWriting() {
        SurveyAccess access = mock(SurveyAccess.class);
        SurveyRecipientMapper mapper = mock(SurveyRecipientMapper.class);
        SurveyRecipient existing = new SurveyRecipient(); existing.setPhone("13800138000");
        when(mapper.selectList(any(Wrapper.class))).thenReturn(Collections.singletonList(existing));
        SurveyRecipientService service = new SurveyRecipientService(access);
        ReflectionTestUtils.setField(service, "baseMapper", mapper);
        List<Map<String, Object>> rows = Arrays.asList(row("+86 13800138000"), row("13900139000"), row("13900139000"), row("not-phone"));
        Map<String, Object> preview = service.preview("form", rows);
        Map<String, Integer> counts = (Map<String, Integer>) preview.get("counts");
        for (String status : Arrays.asList("NEW", "EXISTING", "DUPLICATE", "INVALID")) assertEquals(Integer.valueOf(1), counts.get(status));
        verify(mapper, never()).insert(any());
        verify(access).requireForm("form");
    }

    @Test
    public void normalizationNeverAcceptsArbitraryCountryCodes() {
        assertEquals("13800138000", SurveyRecipientService.normalizePhone("+86 138-0013-8000"));
        assertEquals("13800138000", SurveyRecipientService.normalizePhone("8613800138000"));
        assertFalse(SurveyRecipientService.validPhone(SurveyRecipientService.normalizePhone("+113800138000")));
        assertEquals("'=HYPERLINK(x)", SurveyRecipientService.safeExcel("=HYPERLINK(x)"));
    }

    @Test
    public void rejectsMassiveImportsBeforeDatabaseAccess() {
        SurveyAccess access = mock(SurveyAccess.class);
        SurveyRecipientService service = new SurveyRecipientService(access);
        assertThrows(BaseException.class, () -> service.preview("form", Collections.nCopies(10001, row("13800138000"))));
    }

    private static Map<String, Object> row(String phone) {
        Map<String, Object> row = new LinkedHashMap<>(); row.put("phone", phone); return row;
    }
}
