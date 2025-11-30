// src/main/java/com/shopping/config/CashfreeConfig.java
package com.shopping.config;

import lombok.Data;
import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.stereotype.Component;

@Component
@ConfigurationProperties(prefix = "cashfree")
@Data
public class CashfreeConfig {
    private String appId;
    private String secretKey;
    private String baseUrl;          // not used directly, but kept
    private String merchantUpiId;
    private String webhookSecret;
    private boolean sandbox = false;  // default true

    public String getAppId() { return appId != null ? appId.trim() : null; }
    public String getSecretKey() { return secretKey != null ? secretKey.trim() : null; }
    public String getMerchantUpiId() { return merchantUpiId != null ? merchantUpiId.trim() : null; }
    public String getWebhookSecret() { return webhookSecret != null ? webhookSecret.trim() : null; }

    public String getBaseApiUrl() {
        return sandbox
                ? "https://sandbox.cashfree.com/pg"
                : "https://api.cashfree.com/pg";
    }

    public String getPaymentDomain() {
        return sandbox
                ? "https://sandbox.cashfree.com/pg/orders/pay/"
                : "https://payments.cashfree.com/orders/pay_";
    }
}