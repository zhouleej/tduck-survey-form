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


}
