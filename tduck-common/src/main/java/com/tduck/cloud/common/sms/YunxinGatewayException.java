package com.tduck.cloud.common.sms;

import com.tduck.cloud.common.exception.BaseException;
import lombok.Getter;

/** uncertain=true 表示请求可能已经受理，不能自动再次发送。 */
@Getter
public class YunxinGatewayException extends BaseException {
    private final boolean uncertain;
    private final String providerCode;

    public YunxinGatewayException(String message, boolean uncertain, String providerCode) {
        super(message);
        this.uncertain = uncertain;
        this.providerCode = providerCode;
    }
}
