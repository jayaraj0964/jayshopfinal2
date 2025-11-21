package com.shopping.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.entity.Orders;
import com.shopping.repository.OrderRepository;
import com.shopping.service.CashfreeService;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.transaction.Transactional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final CashfreeService cashfreeService;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    @Transactional
   @PostMapping("/webhook/cashfree")
    public ResponseEntity<String> handleCashfreeWebhook(
            @RequestBody String rawBody,                          // Raw string – no cleaning
            @RequestHeader("x-webhook-timestamp") String timestamp,
            @RequestHeader("X-Webhook-Signature") String signature,
            HttpServletRequest request) {

        log.info("=== CASHFREE WEBHOOK HIT FROM {} ===", request.getRemoteAddr());
        log.info("Timestamp: {}", timestamp);
        log.info("Received Signature: {}", signature);
        log.info("Raw Body Length: {}", rawBody.length());

        try {
            JsonNode json = objectMapper.readTree(rawBody);
            String eventType = json.path("type").asText();

            // TEST EVENT (Manual "Send Test Event" from dashboard) – Skip signature
            if ("WEBHOOK".equals(eventType)) {
                log.info("TEST WEBHOOK RECEIVED – Skipping signature verification");
                log.info("Test Payload: {}", rawBody);
                return ResponseEntity.ok("OK");
            }

            // REAL PAYMENT EVENT – Verify signature
            if (!cashfreeService.verifyWebhookSignature(rawBody, signature, timestamp)) {
                log.warn("REAL WEBHOOK – Signature mismatch (possible replay attack)");
                return ResponseEntity.ok("OK"); // Still acknowledge to stop retries
            }

            log.info("REAL WEBHOOK – SIGNATURE VERIFIED SUCCESSFULLY!");

            // Process real payment success
            String cfOrderId = json.path("data").path("order").path("order_id").asText();
            String cfPaymentId = json.path("data").path("payment").path("cf_payment_id").asText();

            Orders order = orderRepository.findByCashfreeOrderId(cfOrderId).orElse(null);
            if (order != null && "PENDING".equals(order.getStatus())) {
                order.setStatus("PAID");
                order.setTransactionId(cfPaymentId);
                orderRepository.save(order);
                log.info("ORDER {} MARKED AS PAID | Payment ID: {}", order.getId(), cfPaymentId);
            } else {
                log.info("Order {} already PAID or not found", cfOrderId);
            }

        } catch (Exception e) {
            log.error("Webhook processing error", e);
        }

        return ResponseEntity.ok("OK");
    }
}
