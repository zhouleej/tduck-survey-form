package com.tduck.cloud.form.survey;

import cn.hutool.crypto.digest.DigestUtil;
import cn.hutool.json.JSONObject;

import com.tduck.cloud.common.sms.SmsPlatformProperties;

import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;
import java.util.*;

/** 正式开放查询的显式版本适配；JX_20261026 已用正式接口核实，非网页登录接口兜底。 */
public final class SurveyReceiptNormalizer {
    private SurveyReceiptNormalizer() {}

    public static JSONObject safe(JSONObject input) {
        return safe(input, new SmsPlatformProperties());
    }

    public static JSONObject safe(JSONObject input, SmsPlatformProperties properties) {
        JSONObject result = new JSONObject();
        for (String key :
                Arrays.asList(
                        "id",
                        "taskid",
                        "phoneno",
                        "subject",
                        "sendstatus",
                        "sendstatustext",
                        "recvstatus",
                        "recvstatustext",
                        "sendtime",
                        "recvtime",
                        "msgid",
                        "custid")) result.set(key, input.getStr(key));
        if ("JX_20261026".equals(properties.getYunxinReceiptProfile())) {
            result.set("sendstatuscode", input.getStr("sendstatuscode"));
            result.set("recvstatuscode", input.getStr("recvstatuscode"));
            result.set("sendstatus", input.getStr("sendstatuscode"));
            result.set("recvstatus", input.getStr("recvstatuscode"));
            result.set("splitSize", input.getStr("splitSize"));
        }
        return result;
    }

    public static LocalDateTime time(String text) {
        try {
            return text == null || text.trim().isEmpty()
                    ? null
                    : LocalDateTime.parse(text, DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"));
        } catch (Exception e) {
            return null;
        }
    }

    public static String identity(JSONObject row) {
        String id = row.getStr("id");
        return DigestUtil.sha256Hex(
                row.getStr("taskid", "")
                        + "|"
                        + row.getStr("phoneno", "")
                        + "|"
                        + (id != null && !id.isEmpty()
                                ? "id:" + id + "|" + row.getStr("sendtime", "")
                                : "composite:"
                                        + row.getStr("sendtime", "")
                                        + "|"
                                        + row.getStr("msgid", "")
                                        + "|"
                                        + row.getStr("custid", "")));
    }

    public static String fingerprint(JSONObject row) {
        return DigestUtil.sha256Hex(
                identity(row)
                        + "|"
                        + row.getStr("sendstatus", "")
                        + "|"
                        + row.getStr("sendstatustext", "")
                        + "|"
                        + row.getStr("recvstatus", "")
                        + "|"
                        + row.getStr("recvstatustext", "")
                        + "|"
                        + row.getStr("sendtime", "")
                        + "|"
                        + row.getStr("recvtime", "")
                        + "|"
                        + row.getStr("splitSize", ""));
    }

    public static String sendStatus(JSONObject row, SmsPlatformProperties p) {
        String code = row.getStr("sendstatus");
        if ("0".equals(code)) return "SUCCESS";
        if (known(code, p.getYunxinSendFailureCodes())) return "FAILED";
        return "UNKNOWN";
    }

    public static String deliveryStatus(JSONObject row, SmsPlatformProperties p) {
        String code = row.getStr("recvstatus");
        if ("JX_20261026".equals(p.getYunxinReceiptProfile()) && "DELIVRD".equals(code))
            return "SUCCESS";
        if ("0".equals(code) && "DELIVRD".equals(row.getStr("recvstatustext"))) return "SUCCESS";
        if (known(code, p.getYunxinDeliveryFailureCodes())) return "FAILED";
        return code == null || code.isEmpty() ? "WAITING" : "UNKNOWN";
    }

    private static boolean known(String code, String allowed) {
        return code != null
                && !code.isEmpty()
                && allowed != null
                && Arrays.asList(allowed.split("\\s*,\\s*")).contains(code);
    }
}
