package com.tduck.cloud.form.request;

import lombok.Data;

import javax.validation.constraints.NotBlank;
import javax.validation.constraints.NotEmpty;
import javax.validation.constraints.Pattern;
import javax.validation.constraints.Size;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** 问卷短信邀请，空 templateParams 表示固定模板。 */
@Data
public class FormSmsInviteRequest {

    @NotBlank(message = "请选择问卷")
    private String formKey;

    @NotBlank(message = "请输入邀请模板 ID")
    private String templateId;

    @NotEmpty(message = "请输入收件人手机号")
    @Size(max = 100, message = "每次最多发送 100 个号码")
    private List<@NotBlank @Pattern(regexp = "1[3-9][0-9]{9}", message = "请输入正确的 11 位手机号码") String> phoneNumbers;

    @Size(max = 30, message = "最多支持 30 个模板变量")
    private Map<String, Object> templateParams = new LinkedHashMap<>();
}
