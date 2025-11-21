package com.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.config.CashfreeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
@Service
@RequiredArgsConstructor
@Slf4j
public class CashfreeService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CashfreeConfig cashfreeConfig;

    public static class CreateOrderResult {
        public String orderId;
        public Double amount;
        public String qrCodeUrl;
        public String paymentSessionId;
        public String paymentLink;
    }

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", cashfreeConfig.getAppId());
        headers.set("x-client-secret", cashfreeConfig.getSecretKey());
        headers.set("x-api-version", "2025-01-01");
        headers.setContentType(MediaType.APPLICATION_JSON);
        headers.setAccept(java.util.Collections.singletonList(MediaType.APPLICATION_JSON));
        return headers;
    }

    public CreateOrderResult createOrder(Long dbOrderId, double amount, String email, String name, String phone) {
        String url = "https://api.cashfree.com/pg/orders";

        // UNIQUE ORDER ID – NO 409 CONFLICT
        String orderId = "ORD_" + dbOrderId + "_" + System.currentTimeMillis();

        Map<String, Object> body = new HashMap<>();
        body.put("order_id", orderId);
        body.put("order_amount", amount);
        body.put("order_currency", "INR");

        Map<String, Object> customer = new HashMap<>();
        customer.put("customer_id", "cust_" + dbOrderId);
        customer.put("customer_name", name);
        customer.put("customer_email", email);
        customer.put("customer_phone", phone);
        body.put("customer_details", customer);

        Map<String, Object> meta = new HashMap<>();
        meta.put("return_url", "https://jayshopy-ma48.vercel.app/order-success");
        meta.put("notify_url", "https://jayshopfinal2.onrender.com/api/user/webhook/cashfree");
        body.put("order_meta", meta);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, getHeaders());

        try {
            ResponseEntity<String> response = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(response.getBody());

            CreateOrderResult result = new CreateOrderResult();
            result.orderId = root.path("order_id").asText();
            result.paymentSessionId = root.path("payment_session_id").asText();
            result.paymentLink = "https://payments.cashfree.com/orders/pay_" + result.paymentSessionId;
            result.qrCodeUrl = generateUpiQr(orderId, amount, cashfreeConfig.getMerchantUpiId());

            log.info("CASHFREE ORDER SUCCESS → DB: {} | CF: {} | Link: {}", dbOrderId, result.orderId, result.paymentLink);
            return result;

        } catch (Exception e) {
            log.error("Cashfree order failed", e);
            throw new RuntimeException("Payment gateway busy. Try again in 10 seconds.");
        }
    }

    private String generateUpiQr(String orderId, double amount, String vpa) {
        if (vpa == null) return null;
        String upi = String.format("upi://pay?pa=%s&pn=JayShoppy&am=%.2f&cu=INR&tr=%s", vpa, amount, orderId);
        return "https://api.qrserver.com/v1/create-qr-code/?size=400x400&data=" + URLEncoder.encode(upi, StandardCharsets.UTF_8);
    }

    // FINAL WEBHOOK VERIFICATION WITH YOUR SECRET KEY
      public boolean verifyWebhookSignature(String payload, String receivedSignature, String timestamp) {
    try {
        String data = timestamp + "." + payload;

        Mac mac = Mac.getInstance("HmacSHA256");
        SecretKeySpec key = new SecretKeySpec(
            cashfreeConfig.getWebhookSecret().getBytes(StandardCharsets.UTF_8),  // ← ENV VAR
            "HmacSHA256"
        );
        mac.init(key);

        byte[] hash = mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        String computed = Base64.getEncoder().encodeToString(hash);

        log.info("Webhook Signature → Received: {} | Computed: {}", receivedSignature, computed);

        return computed.equals(receivedSignature);

    } catch (Exception e) {
        log.error("Signature verification failed", e);
        return false;
    }
}
}