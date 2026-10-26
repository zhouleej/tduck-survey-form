package com.tduck.cloud.common.sms;

import cn.hutool.core.util.StrUtil;
import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.http.HttpResponse;
import cn.hutool.http.HttpUtil;
import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.tduck.cloud.common.exception.BaseException;

import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 云信开放协议边界。不依赖网页登录态，不自动重放任何请求。 */
public class YunxinClient {
    private final SmsPlatformProperties properties;

    public YunxinClient(SmsPlatformProperties properties) {
        YunxinSmsServiceImpl.validateProperties(properties);
        this.properties = properties;
    }

    public String accountKey() {
        return DigestUtil.sha256Hex(
                properties.getYunxinBaseUrl()
                        + "|"
                        + properties.getYunxinUserId()
                        + "|"
                        + properties.getYunxinAccessNumber()
                        + "|"
                        + properties.getYunxinExtendCode());
    }

    public String configurationVersion() {
        return DigestUtil.sha256Hex(JSONUtil.toJsonStr(properties));
    }

    public JSONObject send(
            String templateId, List<Map<String, Object>> recipients, boolean variable) {
        check(StrUtil.isNotBlank(templateId), "请选择短信模板");
        check(
                recipients != null && !recipients.isEmpty() && recipients.size() <= 100,
                "每个网关请求必须包含1至100个号码");
        Set<String> phones = new LinkedHashSet<>();
        for (Map<String, Object> recipient : recipients) {
            String phone = String.valueOf(recipient.get("phone"));
            check(phone.matches("1[3-9][0-9]{9}") && phones.add(phone), "号码无效或网关分片中存在重复号码");
            check(recipient.size() <= 31, "云信文本模板最多支持30个变量");
            for (Map.Entry<String, Object> entry : recipient.entrySet()) {
                if ("phone".equals(entry.getKey())) continue;
                check(variable && entry.getKey().matches("p([1-9]|[12][0-9]|30)"), "云信变量名须为p1至p30");
                Object value = entry.getValue();
                check(value instanceof CharSequence || value instanceof Number, "云信变量值必须为文字或数字");
                String text = value.toString();
                check(
                        StrUtil.isNotBlank(text) && text.codePointCount(0, text.length()) <= 20,
                        "云信每个变量须为1至20个字符");
            }
            check(!variable || recipient.size() > 1, "变量模板缺少变量");
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("taskid", properties.getYunxinAccessNumber() + properties.getYunxinExtendCode());
        body.put("templateId", templateId);
        body.put("sendnumber", variable ? Collections.emptyList() : new ArrayList<>(phones));
        body.put("varData", variable ? recipients : Collections.emptyList());
        return call(
                StrUtil.blankToDefault(
                        properties.getYunxinSendPath(), "/prod-api/tripartite/task/v2/send"),
                body,
                "0000000",
                true);
    }

    public JSONObject createTextTemplate(String name, String content, boolean variable) {
        check(properties.isYunxinTemplateCreateEnabled(), "文本模板申请尚未启用");
        check(StrUtil.isNotBlank(name) && StrUtil.isNotBlank(content), "请输入模板名称及正文");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("name", name);
        body.put("smscontent", content);
        body.put("mouldtype", variable ? "8" : "6");
        return call("/prod-api/tripartite/template/establishsms", body, "0000000", true);
    }

    public JSONObject templateStatus(String templateId) {
        check(properties.isYunxinTemplateStatusEnabled(), "模板审核查询尚未完成联调或未启用");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("templateid", templateId);
        return call(
                "/prod-api/tripartite/template/status",
                body,
                properties.getYunxinAuditSuccessCode(),
                false);
    }

    public JSONObject receipts(
            String taskId, LocalDateTime begin, LocalDateTime end, int page, int size) {
        check(properties.isYunxinReceiptEnabled(), "回执查询尚未完成联调或未启用");
        check(
                StrUtil.isNotBlank(taskId) && begin != null && end != null && end.isAfter(begin),
                "回执查询范围无效");
        check(
                "beginTime".equals(properties.getYunxinQueryStartField())
                        || "startTime".equals(properties.getYunxinQueryStartField()),
                "请配置已验证的查询起始字段");
        check(
                "yyyy-MM-dd HH:mm:ss".equals(properties.getYunxinQueryTimeFormat())
                        || "yyyyMMdd HH:mm:ss".equals(properties.getYunxinQueryTimeFormat()),
                "请配置已验证的查询时间格式");
        DateTimeFormatter format =
                DateTimeFormatter.ofPattern(properties.getYunxinQueryTimeFormat());
        Map<String, Object> body = paging(page, size);
        body.put("taskid", taskId);
        body.put(properties.getYunxinQueryStartField(), begin.format(format));
        body.put("endTime", end.format(format));
        return call(
                "/prod-api/tripartite/task/query",
                body,
                properties.getYunxinQuerySuccessCode(),
                false);
    }

    public JSONObject upstream(LocalDateTime begin, LocalDateTime end, int page, int size) {
        check(properties.isYunxinUpstreamEnabled(), "上行查询尚未完成联调或未启用");
        check(
                begin != null
                        && end != null
                        && end.isAfter(begin)
                        && !end.isAfter(begin.plusHours(24)),
                "上行查询窗口必须大于0且不超过24小时");
        DateTimeFormatter format = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss");
        Map<String, Object> body = paging(page, size);
        body.put("startTime", begin.format(format));
        body.put("endTime", end.format(format));
        return call(
                "/prod-api/tripartite/upstream",
                body,
                properties.getYunxinUpstreamSuccessCode(),
                false);
    }

    private Map<String, Object> paging(int page, int size) {
        check(page > 0 && size > 0 && size <= 100, "查询分页范围无效");
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("pageNum", page);
        body.put("pageSize", size);
        return body;
    }

    private JSONObject call(
            String path, Map<String, Object> body, String successCode, boolean mutation) {
        check(StrUtil.isNotBlank(successCode), "请先配置正式接口已验证的成功码");
        String timestamp =
                LocalDateTime.now(ZoneId.of("Asia/Shanghai"))
                        .format(DateTimeFormatter.ofPattern("MMddHHmmss"));
        body.put("username", properties.getYunxinUsername());
        body.put("timestamp", timestamp);
        String sign =
                DigestUtil.md5Hex(
                        properties.getYunxinUserId()
                                + properties.getYunxinAccessNumber()
                                + properties.getYunxinExtendCode()
                                + timestamp);
        try (HttpResponse response =
                HttpUtil.createPost(StrUtil.removeSuffix(properties.getYunxinBaseUrl(), "/") + path)
                        .header("Content-Type", "application/json;charset=utf-8")
                        .header("Accept", "application/json")
                        .header("sign", sign)
                        .timeout(15000)
                        .setFollowRedirects(false)
                        .body(JSONUtil.toJsonStr(body))
                        .execute()) {
            if (!response.isOk())
                throw new YunxinGatewayException(
                        "云信响应异常（HTTP " + response.getStatus() + "）", mutation, null);
            JSONObject result;
            try {
                result = JSONUtil.parseObj(response.body());
            } catch (Exception e) {
                throw new YunxinGatewayException("云信返回无效响应", mutation, null);
            }
            String code = result.getStr("code");
            if (!successCode.equals(code)) {
                boolean ambiguous =
                        StrUtil.isBlank(code)
                                || Arrays.asList("200", "0", "00000000").contains(code);
                throw new YunxinGatewayException(
                        "云信业务响应异常（错误码：" + StrUtil.blankToDefault(code, "缺失") + "）",
                        mutation && ambiguous,
                        code);
            }
            return result;
        } catch (YunxinGatewayException e) {
            throw e;
        } catch (Exception e) {
            throw new YunxinGatewayException("云信连接异常或请求超时，请先核实受理结果", mutation, null);
        }
    }

    private static void check(boolean valid, String message) {
        if (!valid) throw new BaseException(message);
    }
}
