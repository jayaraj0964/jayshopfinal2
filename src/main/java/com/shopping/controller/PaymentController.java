// src/main/java/com/shopping/controller/PaymentController.java
package com.shopping.controller;

import com.shopping.config.CashfreeConfig;
import com.shopping.entity.Orders;
import com.shopping.entity.User;
import com.shopping.repository.OrderRepository;
import com.shopping.repository.UserRepository;
import com.shopping.service.CartService;
import com.shopping.service.CashfreeService;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.time.LocalDateTime;
import java.util.Map;

@RestController
@RequestMapping("/api/user")
@RequiredArgsConstructor
@Slf4j
@CrossOrigin(origins = {"http://localhost:5173", "https://jayshopy-ma48.vercel.app"})
public class PaymentController {

    private final UserRepository userRepository;
    private final OrderRepository orderRepository;
    private final CashfreeService cashfreeService;
    private final CashfreeConfig cashfreeConfig;
    private final CartService cartService;

    @PostMapping("/create-payment-session")
    public ResponseEntity<?> createPaymentSession(@RequestBody Map<String, Object> req, Authentication auth) {
        try {
            User user = userRepository.findByEmail(auth.getName())
                    .orElseThrow(() -> new RuntimeException("User not found"));

            Double amount = Double.valueOf(req.get("amount").toString());
            @SuppressWarnings("unchecked")
            Map<String, Object> shippingAddress = (Map<String, Object>) req.get("shippingAddress");

            // 1. Create DB Order
            Orders order = new Orders();
            order.setUser(user);
            order.setTotal(amount);
            order.setStatus("PENDING");
            order.setShippingAddress(shippingAddress.toString());
            order.setOrderDate(LocalDateTime.now());
            order = orderRepository.save(order);

            // 2. Create Cashfree Order
            var result = cashfreeService.createOrder(
                    order.getId(),
                    amount,
                    user.getEmail(),
                    user.getName() != null && !user.getName().isBlank() ? user.getName() : "Customer",
                    user.getPhone()
            );

            // 3. Update DB with Cashfree Order ID
            order.setCashfreeOrderId(result.orderId());
            orderRepository.save(order);

            return ResponseEntity.ok(Map.of(
                    "payment_session_id", result.paymentSessionId(),
                    "payment_link", result.paymentLink(),
                    "order_id", result.orderId()
            ));

        } catch (Exception e) {
            log.error("Payment session failed", e);
            return ResponseEntity.status(500)
                    .body(Map.of("error", e.getMessage()));
        }
    }

   @GetMapping("/order-status/{orderId}")
public ResponseEntity<?> getOrderStatus(@PathVariable Long orderId, Authentication auth) {
    try {
        User user = userRepository.findByEmail(auth.getName())
                .orElseThrow(() -> new RuntimeException("User not found"));

        Orders order = orderRepository.findById(orderId)
                .orElseThrow(() -> new RuntimeException("Order not found"));

        // Security: Only allow user to check their own order
        if (!order.getUser().getId().equals(user.getId())) {
            return ResponseEntity.status(403).body(Map.of("error", "Access denied"));
        }

        return ResponseEntity.ok(Map.of(
            "status", order.getStatus(),
            "orderId", order.getId(),
            "total", order.getTotal()
        ));

    } catch (Exception e) {
        log.error("Order status check failed for ID: " + orderId, e);
        return ResponseEntity.status(404).body(Map.of("error", "Order not found"));
    }
} 

// PaymentController.java lo webhook method lo idi add chey

@PostMapping("/webhook/cashfree")
public ResponseEntity<String> cashfreeWebhook(@RequestBody Map<String, Object> payload) {
    log.info("CASHFREE WEBHOOK HIT RA! Payload: {}", payload);

    try {
        String eventType = (String) payload.get("type");
        if (!"PAYMENT_SUCCESS_WEBHOOK".equals(eventType)) return ResponseEntity.ok("OK");

        Map<String, Object> data = (Map<String, Object>) payload.get("data");
        Map<String, Object> order = (Map<String, Object>) data.get("order");
        Map<String, Object> payment = (Map<String, Object>) data.get("payment");

        String cfOrderId = (String) order.get("order_id");
        String status = (String) payment.get("payment_status");

        if ("SUCCESS".equals(status)) {
            Orders dbOrder = orderRepository.findByCashfreeOrderId(cfOrderId).orElse(null);
            if (dbOrder != null && "PENDING".equals(dbOrder.getStatus())) {
                dbOrder.setStatus("PAID");
                dbOrder.setTransactionId((String) payment.get("payment_id"));
                orderRepository.save(dbOrder);

                // CART CLEAR AVUTHUNDI IDI!
                cartService.clearCart(dbOrder.getUser().getId());
                log.info("ORDER PAID & CART CLEARED CART FOR USER: {}", dbOrder.getUser().getId());
            }
        }
    } catch (Exception e) {
        log.error("Webhook error", e);
    }
    return ResponseEntity.ok("OK");
}

}