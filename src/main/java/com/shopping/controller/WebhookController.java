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
        @RequestBody String rawBody,
        @RequestHeader("x-webhook-timestamp") String timestamp,
        @RequestHeader("X-Webhook-Signature") String signature,
        HttpServletRequest request) {

    log.info("=== CASHFREE WEBHOOK HIT FROM {} ===", request.getRemoteAddr());
    log.info("Timestamp: {}", timestamp);
    log.info("Received Signature: {}", signature);
    log.info("Raw Body ({} chars): {}", rawBody.length(), rawBody);

    // Use raw body exactly as received → NO trim, NO replace
    if (!cashfreeService.verifyWebhookSignature(rawBody, signature, timestamp)) {
        log.warn("Invalid webhook signature");
        return ResponseEntity.ok("OK");
    }

    log.info("WEBHOOK SIGNATURE VERIFIED – Processing payment...");
    // Now process the actual event
    try {
        JsonNode json = objectMapper.readTree(rawBody);
        String eventType = json.path("type").asText();

        if ("payment.success".equals(eventType) || "PAYMENT_SUCCESS_WEBHOOK".equals(eventType)) {
            String cfOrderId = json.path("data").path("order").path("order_id").asText();
            String cfPaymentId = json.path("data").path("payment").path("cf_payment_id").asText();

            Orders order = orderRepository.findByCashfreeOrderId(cfOrderId).orElse(null);
            if (order != null && !"PAID".equals(order.getStatus())) {
                order.setStatus("PAID");
                order.setTransactionId(cfPaymentId);
                orderRepository.save(order);
                log.info("ORDER ID {} MARKED AS PAID | Payment ID: {}", order.getId(), cfPaymentId);
            } else {
                log.info("Order {} already PAID or not found", cfOrderId);
            }
        }

    } catch (Exception e) {
        log.error("Error processing Cashfree webhook", e);
    }

    return ResponseEntity.ok("OK");
}
}
