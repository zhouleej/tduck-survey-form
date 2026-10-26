package com.tduck.cloud.common.sms;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpException;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;
import com.tduck.cloud.common.exception.BaseException;

import java.net.URI;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;

/**
 * 江西移动云信 V1.5.4，复用系统短信配置与 Hutool HTTP。
 */
public class YunxinSmsServiceImpl extends SmsService {

    public static final int MAX_RECIPIENTS = 100;
    private static final String DEFAULT_SEND_PATH = "/prod-api/tripartite/task/v2/send";
    private static final DateTimeFormatter TIMESTAMP_FORMAT = DateTimeFormatter.ofPattern("MMddHHmmss");
    private final String sendUrl;

    public YunxinSmsServiceImpl(SmsPlatformProperties properties) {
        validateProperties(properties);
        this.properties = properties;
        this.sendUrl = StrUtil.removeSuffix(properties.getYunxinBaseUrl(), "/")
                + StrUtil.blankToDefault(properties.getYunxinSendPath(), DEFAULT_SEND_PATH);
    }

    /** 保存前及构建发送器时都校验，不访问网关。 */
    public static void validateProperties(SmsPlatformProperties properties) {
        require(properties != null, "请先配置移动云云信");
        require(StrUtil.isNotBlank(properties.getYunxinUsername()), "请输入云信用户名");
        require(matches(properties.getYunxinUserId(), "[0-9]{12}"), "云信企业 ID 必须为 12 位数字");
        require(matches(properties.getYunxinAccessNumber(), "[0-9]{8}"), "云信接入号必须为 8 位数字");
        require(matches(properties.getYunxinExtendCode(), "[0-9]{6}"), "云信扩展码必须为 6 位数字");
        try {
            URI uri = new URI(properties.getYunxinBaseUrl());
            require(("http".equals(uri.getScheme()) || "https".equals(uri.getScheme()))
                    && uri.getHost() != null && uri.getUserInfo() == null
                    && uri.getQuery() == null && uri.getFragment() == null
                    && (StrUtil.isEmpty(uri.getPath()) || "/".equals(uri.getPath())),
                    "云信网关地址只能包含 http(s) 协议、主机和端口");
        } catch (Exception e) {
            throw new BaseException("云信网关地址只能包含 http(s) 协议、主机和端口");
        }
        String path = StrUtil.blankToDefault(properties.getYunxinSendPath(), DEFAULT_SEND_PATH);
        require(DEFAULT_SEND_PATH.equals(path) || "/prod-api/tripartite/task/send".equals(path),
                "请选择文档中的云信发送接口路径");
    }

    @Override
    public boolean sendSms(String phoneNumber, String templateId, Map<String, Object> templateParams) {
        Map<String, Object> params = templateParams;
        // 原有验证码模块使用 code 命名，云信模板 #params_1# 对应 p1。
        if (params != null && params.size() == 1 && params.containsKey("code")) {
            params = Collections.singletonMap("p1", params.get("code"));
        }
        sendBatchSms(Collections.singletonList(phoneNumber), templateId, params);
        return true;
    }

    /** 单次提交同一模板、同一组变量的问卷邀请；空变量表示普通模板。 */
    public JSONObject sendBatchSms(List<String> phoneNumbers, String templateId, Map<String, Object> templateParams) {
        require(StrUtil.isNotBlank(templateId), "请输入已审核通过的短信模板 ID");
        require(phoneNumbers != null && !phoneNumbers.isEmpty() && phoneNumbers.size() <= MAX_RECIPIENTS,
                "每次请提供 1 至 100 个手机号码");
        List<String> phones = new ArrayList<>(new LinkedHashSet<>(phoneNumbers));
        for (String phone : phones) {
            require(matches(phone, "1[3-9][0-9]{9}"), "请输入正确的 11 位手机号码");
        }
        Map<String, Object> variables = new LinkedHashMap<>();
        if (templateParams != null) {
            require(templateParams.size() <= 30, "云信文本模板最多支持 30 个变量");
            for (Map.Entry<String, Object> entry : templateParams.entrySet()) {
                require(matches(entry.getKey(), "p([1-9]|[12][0-9]|30)"), "云信变量名须为 p1 至 p30");
                Object value = entry.getValue();
                require(value instanceof CharSequence || value instanceof Number, "云信变量值必须为文字或数字");
                String text = value.toString();
                require(StrUtil.isNotBlank(text) && text.codePointCount(0, text.length()) <= 20,
                        "云信每个变量须为 1 至 20 个字符，请勿直接传入完整长链接");
                variables.put(entry.getKey(), text);
            }
        }
        String timestamp = LocalDateTime.now(ZoneId.of("Asia/Shanghai")).format(TIMESTAMP_FORMAT);
        String sign = DigestUtil.md5Hex(properties.getYunxinUserId() + properties.getYunxinAccessNumber()
                + properties.getYunxinExtendCode() + timestamp);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskid", properties.getYunxinAccessNumber() + properties.getYunxinExtendCode());
        body.put("username", properties.getYunxinUsername());
        body.put("templateId", templateId);
        body.put("timestamp", timestamp);
        List<Map<String, Object>> varData = new ArrayList<>();
        if (!variables.isEmpty()) {
            for (String phone : phones) {
                Map<String, Object> item = new LinkedHashMap<>(variables);
                item.put("phone", phone);
                varData.add(item);
            }
        }
        body.put("sendnumber", variables.isEmpty() ? phones : Collections.emptyList());
        body.put("varData", varData);
        // 不记录手机号、正文和鉴权信息；不自动重试，避免任务重复受理和扣费。
        try (HttpResponse response = HttpUtil.createPost(sendUrl)
                .header("Content-Type", "application/json;charset=utf-8")
                .header("Accept", "application/json")
                .header("sign", sign)
                .timeout(15000)
                .setFollowRedirects(false)
                .body(JSONUtil.toJsonStr(body)).execute()) {
            require(response.isOk(), "云信响应异常（HTTP " + response.getStatus() + "），请先在云信平台核实任务状态再重试");
            JSONObject result;
            try {
                result = JSONUtil.parseObj(response.body());
            } catch (Exception e) {
                throw new BaseException("云信返回了无效响应，请先在云信平台核实任务状态再重试");
            }
            String code = result.getStr("code");
            require("0000000".equals(code), "云信未受理发送任务（错误码："
                    + StrUtil.blankToDefault(code, "缺失") + "），请到云信平台查看原因");
            return result;
        } catch (HttpException e) {
            throw new BaseException("云信请求超时或连接异常，受理状态未知，请先在云信平台核实任务状态再重试");
        }
    }

    private static boolean matches(String value, String pattern) {
        return value != null && value.matches(pattern);
    }

    private static void require(boolean condition, String message) {
        if (!condition) {
            throw new BaseException(message);
        }
    }
}
