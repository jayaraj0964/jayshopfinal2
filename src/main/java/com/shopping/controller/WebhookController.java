package com.shopping.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.entity.Orders;
import com.shopping.repository.OrderRepository;
import com.shopping.service.CashfreeService;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequiredArgsConstructor
@Slf4j
public class WebhookController {

    private final CashfreeService cashfreeService;
    private final OrderRepository orderRepository;
    private final ObjectMapper objectMapper;

    @PostMapping("/api/user/webhook/cashfree")
    public ResponseEntity<String> handleCashfreeWebhook(
            @RequestBody String body,
            @RequestHeader(value = "x-webhook-timestamp", required = false) String timestamp,
            @RequestHeader(value = "x-webhook-signature", required = false) String signature,
            HttpServletRequest request) {

        log.info("=== CASHFREE WEBHOOK HIT ===");
        log.info("IP: {} | Timestamp: {} | Signature: {}", request.getRemoteAddr(), timestamp, signature);
        log.info("Payload: {}", body);

        // Verify signature
        if (timestamp == null || signature == null) {
            log.warn("Missing headers: timestamp or signature");
            return ResponseEntity.badRequest().body("Missing headers");
        }

        if (!cashfreeService.verifyWebhookSignature(body, signature, timestamp)) {
            String computed = cashfreeService.computeSignature(body, timestamp);
            log.warn("Invalid webhook signature. Received: {} | Computed: {}", signature, computed);
            return ResponseEntity.badRequest().body("Invalid signature");
        }

        try {
            JsonNode json = objectMapper.readTree(body);
            String eventType = json.path("type").asText();
            String orderId = json.path("data").path("order").path("order_id").asText("");
            String cfLinkId = json.path("data").path("order").path("order_tags").path("cf_link_id").asText("");
            String paymentId = json.path("data").path("payment").path("cf_payment_id").asText("");
            String paymentStatus = json.path("data").path("payment").path("payment_status").asText("");

            log.info("Webhook Event: {}", eventType);
            log.info("Cashfree OrderId: {}", orderId);
            log.info("CF Link ID: {}", cfLinkId);
            log.info("PaymentId: {} | Status: {}", paymentId, paymentStatus);

            if (cfLinkId.isEmpty()) {
                log.error("No cf_link_id in webhook payload");
                return ResponseEntity.ok("Ignored");
            }

            Orders order = orderRepository.findByCfLinkId(cfLinkId);
            if (order == null) {
                log.error("Order not found in DB for cf_link_id: {}", cfLinkId);
                return ResponseEntity.ok("Ignored");
            }

            log.info("DB Order {} current status: {}", order.getId(), order.getStatus());

            // Handle event types
            if ("PAYMENT_SUCCESS".equalsIgnoreCase(eventType)
                    || "order.paid".equalsIgnoreCase(eventType)
                    || "PAYMENT_SUCCESS_WEBHOOK".equalsIgnoreCase(eventType)) {
                order.setStatus("PAID");
                order.setTransactionId(paymentId);
                orderRepository.saveAndFlush(order);
                log.info("Order {} marked as PAID", order.getId());
            } else if ("PAYMENT_FAILED".equalsIgnoreCase(eventType)
                    || "payment.failed".equalsIgnoreCase(eventType)) {
                order.setStatus("FAILED");
                order.setTransactionId(paymentId);
                orderRepository.saveAndFlush(order);
                log.info("Order {} marked as FAILED", order.getId());
            } else {
                log.info("Unhandled event type: {}", eventType);
            }

        } catch (Exception e) {
            log.error("Webhook processing failed", e);
        }

        return ResponseEntity.ok("OK");
    }
}
