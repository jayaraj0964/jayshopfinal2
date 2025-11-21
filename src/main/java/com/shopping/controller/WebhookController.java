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
        @RequestBody String body,
        @RequestHeader("x-webhook-timestamp") String timestamp,
        @RequestHeader("x-webhook-signature") String signature) {

    log.info("CASHFREE WEBHOOK HIT | Timestamp: {} | Signature: {}", timestamp, signature);

    if (!cashfreeService.verifyWebhookSignature(body, signature, timestamp)) {
        log.warn("Invalid webhook signature");
        return ResponseEntity.ok("OK"); // Still 200 for retry stop
    }

    log.info("WEBHOOK SIGNATURE VERIFIED!");

    try {
        JsonNode json = objectMapper.readTree(body);
        String type = json.path("type").asText();
        String cfOrderId = json.path("data").path("order").path("order_id").asText();

        if ("PAYMENT_SUCCESS_WEBHOOK".equals(type)) {
            Orders order = orderRepository.findByCashfreeOrderId(cfOrderId).orElse(null);
            if (order != null && "PENDING".equals(order.getStatus())) {
                String cfPaymentId = json.path("data").path("payment").path("cf_payment_id").asText();
                order.setStatus("PAID");
                order.setTransactionId(cfPaymentId);
                orderRepository.save(order);
                log.info("ORDER {} MARKED AS PAID!", order.getId());
            }
        }

    } catch (Exception e) {
        log.error("Webhook processing failed", e);
    }

    return ResponseEntity.ok("OK");
}
}
