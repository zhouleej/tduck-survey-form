package com.tduck.cloud.common.sms;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.sun.net.httpserver.HttpServer;
import com.tduck.cloud.common.exception.BaseException;
import com.tduck.cloud.common.util.JsonUtils;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Arrays;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.Assert.*;

/** 仅访问本地模拟网关，不发送真实短信。 */
public class YunxinSmsServiceImplTest {

    private HttpServer server;
    private SmsPlatformProperties properties;
    private JSONObject requestBody;
    private String requestSign;
    private String requestPath;
    private String contentType;
    private String responseBody;
    private int status;
    private final AtomicInteger calls = new AtomicInteger();

    @Before
    public void setUp() throws Exception {
        responseBody = "{\"code\":\"0000000\",\"taskid\":\"task-1\",\"msgid\":\"msg-1\"}";
        status = 200;
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", exchange -> {
            calls.incrementAndGet();
            requestSign = exchange.getRequestHeaders().getFirst("sign");
            contentType = exchange.getRequestHeaders().getFirst("Content-Type");
            requestPath = exchange.getRequestURI().getPath();
            try (InputStream in = exchange.getRequestBody(); ByteArrayOutputStream out = new ByteArrayOutputStream()) {
                byte[] buffer = new byte[1024];
                int n;
                while ((n = in.read(buffer)) != -1) {
                    out.write(buffer, 0, n);
                }
                requestBody = JSONUtil.parseObj(new String(out.toByteArray(), StandardCharsets.UTF_8));
            }
            exchange.getResponseHeaders().set("Content-Type", "application/json;charset=utf-8");
            byte[] bytes = responseBody.getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(status, bytes.length);
            exchange.getResponseBody().write(bytes);
            exchange.close();
        });
        server.start();
        properties = new SmsPlatformProperties();
        properties.setType(SmsTypeEnum.YUNXIN);
        properties.setYunxinBaseUrl("http://127.0.0.1:" + server.getAddress().getPort());
        properties.setYunxinUsername("test-user");
        properties.setYunxinUserId("000000000001");
        properties.setYunxinAccessNumber("10650001");
        properties.setYunxinExtendCode("000001");
    }

    @After
    public void tearDown() {
        server.stop(0);
    }

    @Test
    public void fixedTemplateUsesDeduplicatedNumbersAndSignedTimestamp() {
        LocalDateTime before = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).minusSeconds(1);
        JSONObject result = sender().sendBatchSms(Arrays.asList("13800138000", "13900139000", "13800138000"), "tpl-1", null);
        LocalDateTime after = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).plusSeconds(1);
        assertEquals("task-1", result.getStr("taskid"));
        assertEquals("/prod-api/tripartite/task/v2/send", requestPath);
        assertEquals("10650001000001", requestBody.getStr("taskid"));
        assertEquals("test-user", requestBody.getStr("username"));
        assertEquals("tpl-1", requestBody.getStr("templateId"));
        assertEquals(2, requestBody.getJSONArray("sendnumber").size());
        assertTrue(requestBody.getJSONArray("varData").isEmpty());
        assertTrue(contentType.contains("application/json"));
        String timestamp = requestBody.getStr("timestamp");
        assertTrue(timestamp.matches("[0-9]{10}"));
        // 补上年份验证时间在实际发送窗口内，避免测试在跨秒时偶发失败。
        LocalDateTime sent = LocalDateTime.parse(before.getYear() + timestamp, DateTimeFormatter.ofPattern("yyyyMMddHHmmss"));
        assertFalse(sent.isBefore(before.withNano(0)));
        assertFalse(sent.isAfter(after));
        assertEquals(DigestUtil.md5Hex("00000000000110650001000001" + timestamp), requestSign);
        assertEquals(1, calls.get());
    }

    @Test
    public void variableTemplateUsesExplicitKeysAndUtf8() {
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("p2", "张先生");
        params.put("p1", "AbCd1234");
        sender().sendBatchSms(Arrays.asList("13800138000", "13900139000"), "tpl-2", params);
        assertTrue(requestBody.getJSONArray("sendnumber").isEmpty());
        assertEquals(2, requestBody.getJSONArray("varData").size());
        JSONObject item = requestBody.getJSONArray("varData").getJSONObject(0);
        assertEquals("13800138000", item.getStr("phone"));
        assertEquals("AbCd1234", item.getStr("p1"));
        assertEquals("张先生", item.getStr("p2"));
    }

    @Test
    public void verificationCodeMapsToFirstVariable() {
        assertTrue(sender().sendSms("13800138000", "code-template", Collections.singletonMap("code", "0123")));
        JSONObject item = requestBody.getJSONArray("varData").getJSONObject(0);
        assertEquals("0123", item.getStr("p1"));
        assertFalse(item.containsKey("code"));
    }

    @Test
    public void supportsExplicitLegacyPathWithoutFallback() {
        properties.setYunxinBaseUrl(properties.getYunxinBaseUrl() + "/");
        properties.setYunxinSendPath("/prod-api/tripartite/task/send");
        sender().sendSms("13800138000", "tpl", Collections.emptyMap());
        assertEquals("/prod-api/tripartite/task/send", requestPath);
        assertEquals(1, calls.get());
    }

    @Test
    public void rejectsInvalidRecipientsAndVariablesBeforeNetworkRequest() {
        YunxinSmsServiceImpl sender = sender();
        assertThrows(BaseException.class, () -> sender.sendBatchSms(Collections.emptyList(), "tpl", null));
        assertThrows(BaseException.class, () -> sender.sendBatchSms(Collections.nCopies(101, "13800138000"), "tpl", null));
        assertThrows(BaseException.class, () -> sender.sendSms("+8613800138000", "tpl", null));
        assertThrows(BaseException.class, () -> sender.sendSms("13800138000", " ", null));
        for (String key : Arrays.asList("phone", "code2", "p0", "p31")) {
            assertThrows(BaseException.class, () -> sender.sendSms("13800138000", "tpl", Collections.singletonMap(key, "x")));
        }
        assertThrows(BaseException.class, () -> sender.sendSms("13800138000", "tpl", Collections.singletonMap("p1", "https://survey.example.com/s/AbCd1234")));
        assertThrows(BaseException.class, () -> sender.sendSms("13800138000", "tpl", Collections.singletonMap("p1", null)));
        assertEquals(0, calls.get());
    }

    @Test
    public void supportsThirtyVariablesAndTwentyCharacterBoundary() {
        Map<String, Object> params = new LinkedHashMap<>();
        for (int i = 1; i <= 30; i++) params.put("p" + i, "12345678901234567890");
        sender().sendSms("13800138000", "tpl", params);
        assertEquals(31, requestBody.getJSONArray("varData").getJSONObject(0).size());
    }

    @Test
    public void onlyDocumentedSendSuccessCodeIsAcceptedAndErrorsAreNotRetried() {
        for (String code : Arrays.asList("200", "0", "00000000", "1001")) {
            responseBody = "{\"code\":\"" + code + "\",\"msg\":\"sensitive supplier message\"}";
            BaseException error = assertThrows(BaseException.class, () -> sender().sendSms("13800138000", "tpl", null));
            assertFalse(error.getMessage().contains("sensitive"));
        }
        assertEquals(4, calls.get());
    }

    @Test
    public void httpAndMalformedResponsesFailWithoutRetry() {
        status = 500;
        assertThrows(BaseException.class, () -> sender().sendSms("13800138000", "tpl", null));
        status = 200;
        responseBody = "<html>bad gateway</html>";
        assertThrows(BaseException.class, () -> sender().sendSms("13800138000", "tpl", null));
        assertEquals(2, calls.get());
    }

    @Test
    public void validatesGatewayAndCrmFields() {
        for (String url : Arrays.asList("ftp://localhost", "http://localhost/prod-api", "http://u:p@localhost", "http://localhost?q=1")) {
            properties.setYunxinBaseUrl(url);
            assertThrows(BaseException.class, this::sender);
        }
        properties.setYunxinBaseUrl("https://gateway.example.com");
        properties.setYunxinUserId("123");
        assertThrows(BaseException.class, this::sender);
        assertEquals(0, calls.get());
    }

    @Test
    public void readsChannelFromExistingJsonConfiguration() {
        SmsPlatformProperties parsed = JsonUtils.jsonToObj("{\"type\":\"YUNXIN\",\"yunxinExtendCode\":\"000001\"}", SmsPlatformProperties.class);
        assertEquals(SmsTypeEnum.YUNXIN, parsed.getType());
        assertEquals("000001", parsed.getYunxinExtendCode());
        assertEquals("/prod-api/tripartite/task/v2/send", parsed.getYunxinSendPath());
        assertEquals(SmsTypeEnum.ALIYUN, JsonUtils.jsonToObj("{\"type\":\"ALIYUN\"}", SmsPlatformProperties.class).getType());
    }

    private YunxinSmsServiceImpl sender() {
        return new YunxinSmsServiceImpl(properties);
    }
}
