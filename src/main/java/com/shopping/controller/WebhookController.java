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

    log.info("=== CASHFREE WEBHOOK HIT FROM IP: {} ===", request.getRemoteAddr());
    log.info("Timestamp: {}", timestamp);
    log.info("Received Signature: {}", signature);
    log.info("Raw Body Length: {}", rawBody.length());
    log.info("Raw Body Preview: {}", rawBody.substring(0, 200));

    // STEP 1: CLEAN PAYLOAD – REMOVE ALL WHITESPACE & NEWLINES (Cashfree exact JSON expect chestundi)
    String cleanPayload = rawBody.trim().replaceAll("\\s+", "");

    // STEP 2: VERIFY SIGNATURE WITH CLEAN PAYLOAD
    if (!cashfreeService.verifyWebhookSignature(cleanPayload, signature, timestamp)) {
        log.warn("SIGNATURE MISMATCH AFTER CLEANING – Returning 200 to stop retries");
        return ResponseEntity.ok("OK");
    }

    log.info("WEBHOOK SIGNATURE VERIFIED SUCCESSFULLY!");

    // STEP 3: PROCESS PAYMENT
    try {
        JsonNode json = objectMapper.readTree(cleanPayload);
        String eventType = json.path("type").asText();
        String cfOrderId = json.path("data").path("order").path("order_id").asText();

        if ("PAYMENT_SUCCESS_WEBHOOK".equals(eventType)) {
            Orders order = orderRepository.findByCashfreeOrderId(cfOrderId).orElse(null);
            if (order != null && "PENDING".equals(order.getStatus())) {
                String cfPaymentId = json.path("data").path("payment").path("cf_payment_id").asText();
                order.setStatus("PAID");
                order.setTransactionId(cfPaymentId);
                orderRepository.save(order);
                log.info("ORDER {} MARKED AS PAID VIA WEBHOOK!", order.getId());
            } else {
                log.warn("Order {} not found or already PAID", cfOrderId);
            }
        }

    } catch (Exception e) {
        log.error("Webhook processing failed", e);
    }

    return ResponseEntity.ok("OK");
}
}
