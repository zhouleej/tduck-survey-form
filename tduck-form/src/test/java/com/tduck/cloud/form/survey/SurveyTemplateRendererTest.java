package com.tduck.cloud.form.survey;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.survey.entity.*;
import cn.hutool.json.JSONObject;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;

public class SurveyTemplateRendererTest {
    @Test public void fiveLinkPartsAreNonemptyBoundedAndReconstructExactly() {
        String link = "https://survey.example.com/i/AbCdEf01234567890123";
        List<String> parts = SurveyTemplateRenderer.splitLink(link, 5);
        assertEquals(link, String.join("", parts));
        assertTrue(parts.stream().allMatch(v -> !v.isEmpty() && v.length() <= 20));
        assertThrows(BaseException.class, () -> SurveyTemplateRenderer.splitLink(String.join("", Collections.nCopies(101, "x")), 5));
    }
    @Test public void renderedLinkMatchesTheActualRecipientVariables() {
        SurveySmsTemplate template = new SurveySmsTemplate(); template.setVariable(true);
        JSONObject map = new JSONObject(); StringBuilder content = new StringBuilder("【调研】请填写：");
        for (int i = 1; i <= 5; i++) { content.append("#params_").append(i).append("#"); map.set("p" + i, new JSONObject().set("source", "LINK_PART").set("part", i).set("parts", 5)); }
        template.setContent(content.toString()); template.setMappingJson(map.toString());
        UserFormEntity form = new UserFormEntity(); form.setFormKey("form");
        String link = "https://survey.example.com/i/AbCdEf01234567890123";
        Map<String, Object> variables = SurveyTemplateRenderer.variables(template, form, new SurveyRecipient(), link, "AbCdEf01234567890123");
        assertEquals("【调研】请填写：" + link, SurveyTemplateRenderer.render(template, variables));
        template.setContent(content.toString().replace("#params_3#", " gap #params_3#"));
        assertThrows(BaseException.class, () -> SurveyTemplateRenderer.validate(template));
    }
    @Test public void missingSourcesAndLongValuesFailBeforeSending() {
        SurveySmsTemplate template = new SurveySmsTemplate(); template.setVariable(true); template.setContent("【调研】#params_1#"); template.setMappingJson("{\"p1\":{\"source\":\"NAME\"}}");
        assertThrows(BaseException.class, () -> SurveyTemplateRenderer.variables(template, new UserFormEntity(), new SurveyRecipient(), "link", "token"));
        template.setMappingJson("{}"); assertThrows(BaseException.class, () -> SurveyTemplateRenderer.validate(template));
    }
}
