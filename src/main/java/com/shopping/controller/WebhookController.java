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

    // SAFE PREVIEW – NO CRASH EVEN IF BODY IS SMALL
    String preview = rawBody.length() > 200 ? rawBody.substring(0, 200) + "..." : rawBody;
    log.info("Body Preview: {}", preview);

    // CLEAN PAYLOAD – Remove all whitespace/newlines
    String payload = rawBody.trim().replaceAll("\\s+", "");

    if (!cashfreeService.verifyWebhookSignature(payload, signature, timestamp)) {
        log.warn("SIGNATURE MISMATCH – But returning 200 to stop retries");
        return ResponseEntity.ok("OK");
    }

    log.info("WEBHOOK SIGNATURE VERIFIED – PAYMENT CONFIRMED!");

    try {
        JsonNode json = objectMapper.readTree(payload);
        String eventType = json.path("type").asText();

        if ("PAYMENT_SUCCESS_WEBHOOK".equals(eventType) || "payment.success".equals(eventType)) {
            String cfOrderId = json.path("data").path("order").path("order_id").asText();
            String cfPaymentId = json.path("data").path("payment").path("cf_payment_id").asText();

            Orders order = orderRepository.findByCashfreeOrderId(cfOrderId).orElse(null);
            if (order != null && !"PAID".equals(order.getStatus())) {
                order.setStatus("PAID");
                order.setTransactionId(cfPaymentId);
                orderRepository.save(order);
                log.info("ORDER {} MARKED AS PAID VIA WEBHOOK!", order.getId());
            }
        }
    } catch (Exception e) {
        log.error("Error processing webhook", e);
    }

    return ResponseEntity.ok("OK");
}
}
