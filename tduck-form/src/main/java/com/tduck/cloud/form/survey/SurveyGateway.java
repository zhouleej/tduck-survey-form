package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import cn.hutool.json.JSONUtil;

import com.tduck.cloud.common.sms.*;

import org.springframework.stereotype.Component;

import java.net.URI;
import java.util.*;

/** 业务层只通过此入口取得当前配置快照；对外仅返回明确允许的能力字段。 */
@Component
public class SurveyGateway {
    public SmsPlatformProperties properties() {
        SmsService sender = SmsConfiguration.getSmsService();
        check(sender instanceof YunxinSmsServiceImpl, "请联系管理员配置移动云云信");
        return JSONUtil.toBean(JSONUtil.toJsonStr(sender.properties), SmsPlatformProperties.class);
    }

    public YunxinClient client() {
        return new YunxinClient(properties());
    }

    public void requireEnabled() {
        check(properties().isSurveyEnabled(), "调研邀请功能尚未启用，请联系管理员");
    }

    public String publicBaseUrl() {
        String url = properties().getSurveyPublicBaseUrl();
        try {
            URI uri = new URI(url);
            check(
                    ("https".equals(uri.getScheme()) || "http".equals(uri.getScheme()))
                            && uri.getHost() != null
                            && uri.getUserInfo() == null
                            && uri.getQuery() == null
                            && uri.getFragment() == null
                            && (uri.getPath().isEmpty() || "/".equals(uri.getPath())),
                    "请配置问卷公开根地址");
            check(
                    !"localhost".equalsIgnoreCase(uri.getHost())
                            && !"127.0.0.1".equals(uri.getHost())
                            && !"::1".equals(uri.getHost()),
                    "短信邀请不能使用localhost或回环地址");
        } catch (Exception e) {
            throw new com.tduck.cloud.common.exception.BaseException("请配置手机可访问的问卷公开根地址");
        }
        return url.replaceAll("/+$", "");
    }

    public Map<String, Object> capabilities() {
        Map<String, Object> result = new LinkedHashMap<>();
        result.put("admin", com.tduck.cloud.common.util.SecurityUtils.isAdmin());
        try {
            result.put("userId", com.tduck.cloud.common.util.SecurityUtils.getUserId().toString());
        } catch (Exception ignored) {
        }
        try {
            SmsPlatformProperties p = properties();
            result.put("enabled", p.isSurveyEnabled());
            result.put("templateCreate", p.isYunxinTemplateCreateEnabled());
            result.put("templateStatus", p.isYunxinTemplateStatusEnabled());
            result.put("receipt", p.isYunxinReceiptEnabled());
            result.put("upstream", p.isYunxinUpstreamEnabled());
            result.put("publicBaseUrl", p.getSurveyPublicBaseUrl());
            result.put("sendStartHour", p.getSurveySendStartHour());
            result.put("sendEndHour", p.getSurveySendEndHour());
            result.put("reason", p.isSurveyEnabled() ? "" : "调研邀请尚未启用，可先准备名单及模板");
        } catch (Exception e) {
            result.put("enabled", false);
            result.put("reason", "尚未配置云信渠道");
        }
        return result;
    }
}
