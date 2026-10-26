package com.tduck.cloud.form.survey;

import static org.junit.Assert.*;

import cn.hutool.json.*;

import com.tduck.cloud.common.sms.SmsPlatformProperties;

import org.junit.Test;

public class SurveyReceiptNormalizerTest {
    @Test
    public void verifiedGatewayProfileMapsActualOpenApiFieldsExplicitly() {
        SmsPlatformProperties properties = new SmsPlatformProperties();
        JSONObject input =
                JSONUtil.parseObj(
                        "{\"sendstatuscode\":\"0\",\"recvstatuscode\":\"DELIVRD\",\"recvstatustext\":\"成功\",\"splitSize\":5}");
        assertEquals(
                "UNKNOWN",
                SurveyReceiptNormalizer.sendStatus(
                        SurveyReceiptNormalizer.safe(input), properties));
        properties.setYunxinReceiptProfile("JX_20261026");
        JSONObject row = SurveyReceiptNormalizer.safe(input, properties);
        assertEquals("SUCCESS", SurveyReceiptNormalizer.sendStatus(row, properties));
        assertEquals("SUCCESS", SurveyReceiptNormalizer.deliveryStatus(row, properties));
        assertEquals("5", row.getStr("splitSize"));
    }

    @Test
    public void statusDimensionsAndUnknownCodesStaySeparate() {
        SmsPlatformProperties p = new SmsPlatformProperties();
        JSONObject row =
                JSONUtil.parseObj(
                        "{\"sendstatus\":\"0\",\"recvstatus\":\"\",\"recvstatustext\":\"\"}");
        assertEquals("SUCCESS", SurveyReceiptNormalizer.sendStatus(row, p));
        assertEquals("WAITING", SurveyReceiptNormalizer.deliveryStatus(row, p));
        row.set("recvstatus", "0").set("recvstatustext", "DELIVRD");
        assertEquals("SUCCESS", SurveyReceiptNormalizer.deliveryStatus(row, p));
        row.set("sendstatus", "new-code").set("recvstatus", "9");
        assertEquals("UNKNOWN", SurveyReceiptNormalizer.sendStatus(row, p));
        assertEquals("UNKNOWN", SurveyReceiptNormalizer.deliveryStatus(row, p));
        p.setYunxinDeliveryFailureCodes("8, 9");
        assertEquals("FAILED", SurveyReceiptNormalizer.deliveryStatus(row, p));
    }

    @Test
    public void eventDedupChangesWithStatusButRecordIdentityDoesNot() {
        JSONObject row =
                JSONUtil.parseObj(
                        "{\"id\":\"123\",\"taskid\":\"task\",\"phoneno\":\"13800138000\",\"sendtime\":\"2026-10-26"
                            + " 18:11:01\",\"recvstatus\":\"\"}");
        String identity = SurveyReceiptNormalizer.identity(row),
                first = SurveyReceiptNormalizer.fingerprint(row);
        row.set("recvstatus", "0").set("recvstatustext", "DELIVRD");
        assertEquals(identity, SurveyReceiptNormalizer.identity(row));
        assertNotEquals(first, SurveyReceiptNormalizer.fingerprint(row));
        assertNull(SurveyReceiptNormalizer.time("invalid"));
    }
}
