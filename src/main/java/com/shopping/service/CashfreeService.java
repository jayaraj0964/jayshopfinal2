// src/main/java/com/shopping/service/CashfreeService.java
package com.shopping.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.config.CashfreeConfig;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.*;
import org.springframework.stereotype.Service;
import org.springframework.web.client.RestTemplate;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

@Service
@RequiredArgsConstructor
@Slf4j
public class CashfreeService {

    private final RestTemplate restTemplate;
    private final ObjectMapper objectMapper;
    private final CashfreeConfig cashfreeConfig;

    public record CreateOrderResult(
            String orderId,
            String paymentSessionId,
            String paymentLink
    ) {}

    private HttpHeaders getHeaders() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("x-client-id", cashfreeConfig.getAppId());
        headers.set("x-client-secret", cashfreeConfig.getSecretKey());
        headers.set("x-api-version", "2023-08-01");
        headers.setContentType(MediaType.APPLICATION_JSON);
        return headers;
    }

    public CreateOrderResult createOrder(Long dbOrderId, double amount, String email, String name, String phone) {
        String url = cashfreeConfig.getBaseApiUrl() + "/orders";

        String orderId = "ORD_" + dbOrderId + "_" + System.currentTimeMillis();

        Map<String, Object> body = new HashMap<>();
        body.put("order_id", orderId);
        body.put("order_amount", amount);
        body.put("order_currency", "INR");

        Map<String, Object> customer = new HashMap<>();
        customer.put("customer_id", "cust_" + dbOrderId);
        customer.put("customer_name", name);
        customer.put("customer_email", email);
        customer.put("customer_phone", phone != null && phone.length() == 10 ? phone : "9999999999");
        body.put("customer_details", customer);

        Map<String, Object> meta = new HashMap<>();
        meta.put("return_url", "https://jayshopy008.vercel.app/payment-success?order_id={order_id}");  
        meta.put("notify_url", "https://jayshopfinal2.onrender.com/api/user/webhook/cashfree");
        body.put("order_meta", meta);

        HttpEntity<Map<String, Object>> entity = new HttpEntity<>(body, getHeaders());

        try {
            ResponseEntity<String> resp = restTemplate.postForEntity(url, entity, String.class);
            JsonNode root = objectMapper.readTree(resp.getBody());

            String paymentSessionId = root.path("payment_session_id").asText();
            String paymentLink = cashfreeConfig.getPaymentDomain() + paymentSessionId;

            log.info("Cashfree Order Created → DB: {} | CF: {} | Link: {}", dbOrderId, orderId, paymentLink);

            return new CreateOrderResult(orderId, paymentSessionId, paymentLink);

        } catch (Exception e) {
            log.error("Cashfree order creation failed", e);
            throw new RuntimeException("Payment gateway busy. Try again.");
        }
    }
}