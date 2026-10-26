package com.tduck.cloud.common.sms;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.context.annotation.Configuration;

/**
 * 短信配置类
 *
 * @author smalljop
 */
@Data
@Configuration
@ConfigurationProperties(prefix = "platform.sms")
public class SmsPlatformProperties {

    /**
     * secretId
     */
    private String secretId;


    private SmsTypeEnum type;
    /**
     * 秘钥
     */
    private String secretKey;
    /**
     * appId 腾讯云使用
     */
    private String appId;
    /**
     * 短信签名 如【tduck】 需要去短信平台申请
     */
    private String sign;

    /**
     * 模板id
     */
    private String templateId;

    /** 云信网关根地址（不包含接口路径） */
    private String yunxinBaseUrl;

    /** 云信用户名，与 CRM 企业 ID 不同 */
    private String yunxinUsername;

    /** CRM 分配的 12 位企业 ID */
    private String yunxinUserId;

    /** 8 位接入号，保留前导零 */
    private String yunxinAccessNumber;

    /** 6 位扩展码，保留前导零 */
    private String yunxinExtendCode;

    /** 文档接口定义与示例路径不同，允许按实际网关选择 */
    private String yunxinSendPath = "/prod-api/tripartite/task/v2/send";

    /** 问卷邀请默认模板，与验证码模板分开 */
    private String yunxinInvitationTemplateId;

    /** 调研功能及各开放能力分别启用；未经联调的能力不自动调用。 */
    private boolean surveyEnabled;
    private boolean yunxinTemplateCreateEnabled;
    private boolean yunxinTemplateStatusEnabled;
    private boolean yunxinReceiptEnabled;
    private boolean yunxinUpstreamEnabled;
    private String surveyPublicBaseUrl;
    private int surveySendStartHour = 8;
    private int surveySendEndHour = 20;
    private int surveySendIntervalSeconds = 1;

    /** 查询参数和成功码必须由管理员按正式开放接口联调结果固化。 */
    private String yunxinQueryStartField = "startTime";
    private String yunxinQueryTimeFormat = "yyyy-MM-dd HH:mm:ss";
    private String yunxinQuerySuccessCode = "";
    private String yunxinAuditSuccessCode = "200";
    private String yunxinUpstreamSuccessCode = "200";
    /** 仅使用已验证的状态字典；空白的失败字典不会把陌生码误判为失败。 */
    private String yunxinSendFailureCodes = "";
    private String yunxinDeliveryFailureCodes = "";
    /** DOCUMENT 或正式开放接口实测的 JX_20261026；不自动猜测字段版本。 */
    private String yunxinReceiptProfile = "DOCUMENT";


}
