// // WebhookController.java (FULLY WORKING 2025)
// package com.shopping.controller;

// import com.shopping.entity.Orders;
// import com.shopping.repository.OrderRepository;
// import com.shopping.service.CashfreeService;
// import com.fasterxml.jackson.databind.JsonNode;
// import com.fasterxml.jackson.databind.ObjectMapper;
// import lombok.RequiredArgsConstructor;
// import lombok.extern.slf4j.Slf4j;
// import org.springframework.http.ResponseEntity;
// import org.springframework.web.bind.annotation.*;

// @RestController
// @RequestMapping("/api/user/webhook")
// @RequiredArgsConstructor
// @Slf4j
// public class WebhookController {

//     private final OrderRepository orderRepository;
//     private final CashfreeService cashfreeService;

//     @PostMapping("/cashfree")
//     public ResponseEntity<String> cashfreeWebhook(
//             @RequestBody String payload,  // ← String ga teesko (not Map!)
//             @RequestHeader("x-webhook-timestamp") String timestamp,
//             @RequestHeader("x-webhook-signature") String signature) {

//         try {
//             // 2025 MANDATORY VERIFICATION
//             boolean isValid = cashfreeService.verifyWebhookSignature(payload, signature, timestamp);

//             if (!isValid) {
//                 log.warn("INVALID WEBHOOK SIGNATURE! Possible attack!");
//                 return ResponseEntity.status(400).body("Invalid signature");
//             }

//             // Parse payload safely
//             JsonNode root = new ObjectMapper().readTree(payload);
//             JsonNode data = root.path("data");
//             JsonNode order = data.path("order");
//             JsonNode payment = data.path("payment");

//             String cashfreeOrderId = order.path("order_id").asText();
//             String status = payment.path("payment_status").asText();
//             String cfPaymentId = payment.path("cf_payment_id").asText();

//             log.info("Webhook → Order: {} | Status: {}", cashfreeOrderId, status);

//             Orders dbOrder = orderRepository.findByCashfreeOrderId(cashfreeOrderId)
//                     .orElse(null);

//             if (dbOrder != null && "PENDING".equals(dbOrder.getStatus())) {
//                 if ("SUCCESS".equals(status)) {
//                     dbOrder.setStatus("PAID");
//                     dbOrder.setTransactionId(cfPaymentId);
//                     orderRepository.save(dbOrder);
//                     log.info("ORDER PAID SUCCESSFULLY → {} | ₹{}", cashfreeOrderId, dbOrder.getTotal());
//                 } else if ("FAILED".equals(status)) {
//                     dbOrder.setStatus("FAILED");
//                     orderRepository.save(dbOrder);
//                 }
//             }

//             return ResponseEntity.ok("OK");

//         } catch (Exception e) {
//             log.error("Webhook processing failed", e);
//             return ResponseEntity.status(500).body("Error");
//         }
//     }
// }