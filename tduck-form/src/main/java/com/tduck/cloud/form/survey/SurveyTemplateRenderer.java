package com.tduck.cloud.form.survey;

import static com.tduck.cloud.form.survey.SurveyAccess.check;

import cn.hutool.json.JSONObject;
import cn.hutool.json.JSONUtil;

import com.tduck.cloud.form.entity.UserFormEntity;
import com.tduck.cloud.form.survey.entity.SurveyRecipient;
import com.tduck.cloud.form.survey.entity.SurveySmsTemplate;

import java.util.*;
import java.util.regex.*;

/** 纯渲染器：先验证所有变量，再生成与网关请求完全一致的预览。 */
public final class SurveyTemplateRenderer {
    private static final Pattern PLACEHOLDER = Pattern.compile("#params_([1-9]|[12][0-9]|30)#");
    private static final Set<String> SOURCES =
            new HashSet<>(
                    Arrays.asList(
                            "INVITE_LINK",
                            "INVITE_TOKEN",
                            "FORM_KEY",
                            "FORM_NAME",
                            "NAME",
                            "COMPANY",
                            "FIXED",
                            "LINK_PART"));

    private SurveyTemplateRenderer() {}

    public static Set<String> validate(SurveySmsTemplate template) {
        check(
                template.getContent() != null
                        && !template.getContent().trim().isEmpty()
                        && template.getContent().length() <= 10000,
                "模板正文须为1至10000字符");
        Matcher matcher = PLACEHOLDER.matcher(template.getContent());
        Set<String> keys = new LinkedHashSet<>();
        while (matcher.find()) keys.add("p" + matcher.group(1));
        check(
                !PLACEHOLDER.matcher(template.getContent()).replaceAll("").contains("#params_"),
                "模板包含无效占位符");
        JSONObject mapping;
        try {
            mapping = JSONUtil.parseObj(template.getMappingJson());
        } catch (Exception e) {
            throw new com.tduck.cloud.common.exception.BaseException("变量映射格式无效");
        }
        check(Boolean.TRUE.equals(template.getVariable()) == !keys.isEmpty(), "模板类型与正文占位符不一致");
        check(mapping.keySet().equals(keys), "变量映射必须与正文占位符完全一致");
        for (String key : keys) {
            JSONObject rule = mapping.getJSONObject(key);
            check(rule != null && SOURCES.contains(rule.getStr("source")), "变量来源无效");
            if ("FIXED".equals(rule.getStr("source"))) requireValue(rule.getStr("value"));
            if ("LINK_PART".equals(rule.getStr("source"))) {
                Integer part = rule.getInt("part"), parts = rule.getInt("parts");
                check(
                        parts != null
                                && parts > 0
                                && parts <= 30
                                && part != null
                                && part > 0
                                && part <= parts,
                        "链接分段配置无效");
                // 每个分段只出现一次，并按连续编号在正文中紧邻排列。
                StringBuilder sequence = new StringBuilder();
                for (int i = 1; i <= parts; i++) {
                    String found = null;
                    for (String other : keys) {
                        JSONObject candidate = mapping.getJSONObject(other);
                        if (candidate != null
                                && "LINK_PART".equals(candidate.getStr("source"))
                                && parts.equals(candidate.getInt("parts"))
                                && Integer.valueOf(i).equals(candidate.getInt("part"))) {
                            check(found == null, "链接分段编号重复");
                            found = other;
                        }
                    }
                    check(found != null, "链接分段必须完整连续");
                    sequence.append("#params_").append(found.substring(1)).append("#");
                }
                check(template.getContent().contains(sequence.toString()), "链接分段占位符必须按顺序连续排列");
            }
        }
        return keys;
    }

    public static Map<String, Object> variables(
            SurveySmsTemplate template,
            UserFormEntity form,
            SurveyRecipient recipient,
            String link,
            String token) {
        return compile(template).variables(form, recipient, link, token);
    }

    public static Compiled compile(SurveySmsTemplate template) {
        return new Compiled(validate(template), JSONUtil.parseObj(template.getMappingJson()));
    }

    public static final class Compiled {
        private final Set<String> keys;
        private final JSONObject mapping;

        private Compiled(Set<String> keys, JSONObject mapping) {
            this.keys = keys;
            this.mapping = mapping;
        }

        public Map<String, Object> variables(
                UserFormEntity form, SurveyRecipient recipient, String link, String token) {
            return calculate(keys, mapping, form, recipient, link, token);
        }
    }

    private static Map<String, Object> calculate(
            Set<String> keys,
            JSONObject mapping,
            UserFormEntity form,
            SurveyRecipient recipient,
            String link,
            String token) {
        Map<String, Object> result = new LinkedHashMap<>();
        Map<Integer, List<String>> partsCache = new HashMap<>();
        for (String key : keys) {
            JSONObject rule = mapping.getJSONObject(key);
            String source = rule.getStr("source"), value;
            switch (source) {
                case "INVITE_LINK":
                    value = link;
                    break;
                case "INVITE_TOKEN":
                    value = token;
                    break;
                case "FORM_KEY":
                    value = form.getFormKey();
                    break;
                case "FORM_NAME":
                    value = form.getTextName();
                    break;
                case "NAME":
                    value = recipient.getName();
                    break;
                case "COMPANY":
                    value = recipient.getCompany();
                    break;
                case "LINK_PART":
                    value =
                            partsCache
                                    .computeIfAbsent(
                                            rule.getInt("parts"), count -> splitLink(link, count))
                                    .get(rule.getInt("part") - 1);
                    break;
                default:
                    value = rule.getStr("value");
            }
            try {
                requireValue(value);
            } catch (com.tduck.cloud.common.exception.BaseException e) {
                throw new com.tduck.cloud.common.exception.BaseException(
                        "变量" + key + "缺失或超过20字符，请检查对应资料或映射");
            }
            result.put(key, value);
        }
        return result;
    }

    public static String render(SurveySmsTemplate template, Map<String, Object> variables) {
        Matcher matcher = PLACEHOLDER.matcher(template.getContent());
        StringBuffer result = new StringBuffer();
        while (matcher.find()) {
            String value = Objects.toString(variables.get("p" + matcher.group(1)), "");
            requireValue(value);
            matcher.appendReplacement(result, Matcher.quoteReplacement(value));
        }
        matcher.appendTail(result);
        return result.toString();
    }

    public static List<String> splitLink(String link, int parts) {
        check(
                link != null
                        && parts > 0
                        && parts <= 30
                        && link.length() >= parts
                        && link.length() <= parts * 20,
                "链接超出分段容量或无法生成非空片段，请更换模板");
        List<String> result = new ArrayList<>();
        int offset = 0;
        for (int i = 0; i < parts; i++) {
            int length = link.length() / parts + (i < link.length() % parts ? 1 : 0);
            result.add(link.substring(offset, offset + length));
            offset += length;
        }
        check(String.join("", result).equals(link), "链接拼接校验失败");
        return result;
    }

    private static void requireValue(String value) {
        check(
                value != null
                        && !value.trim().isEmpty()
                        && value.codePointCount(0, value.length()) <= 20,
                "模板变量缺失或超过20字符，请调整模板映射");
    }
}
