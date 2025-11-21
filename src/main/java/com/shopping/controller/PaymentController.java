package com.shopping.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.entity.Orders;
import com.shopping.entity.User;
import com.shopping.repository.OrderRepository;
import com.shopping.repository.UserRepository;
import com.shopping.service.CashfreeService;

import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;

import java.util.HashMap;
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
    private final ObjectMapper objectMapper;  // JSON convert kosam

    // CREATE CARD + UPI PAYMENT (BOTH WORK!)
@PostMapping({"/create-card-payment", "/create-upi-payment"})
    public ResponseEntity<?> createPayment(@RequestBody Map<String, Object> req, Authentication auth) {
        try {
            String email = auth.getName();
            User user = userRepository.findByEmail(email)
                    .orElseThrow(() -> new RuntimeException("User not found"));

            // Extract shipping & total
            @SuppressWarnings("unchecked")
            Map<String, Object> shipping = (Map<String, Object>) req.get("shippingAddress");
            double total = ((Number) req.get("total")).doubleValue();

            if (shipping == null || total <= 0) {
                return ResponseEntity.badRequest().body(Map.of("error", "Invalid data"));
            }

            // 1. CREATE DB ORDER FIRST
            Orders order = new Orders();
            order.setUser(user);
            order.setShippingAddress(objectMapper.writeValueAsString(shipping));
            order.setTotal(total);
            order.setStatus("PENDING");
            order = orderRepository.save(order);  // Save to get ID

            // 2. CREATE CASHFREE ORDER
            CashfreeService.CreateOrderResult result = cashfreeService.createOrder(
                order.getId(),
                total,
                user.getEmail(),
                user.getName() != null && !user.getName().isEmpty() ? user.getName() : "Customer",
                user.getPhone()
            );

            // 3. SAVE CASHFREE ORDER ID IN DB (MANDATORY FOR WEBHOOK!)
            order.setCashfreeOrderId(result.orderId);
            orderRepository.save(order);

            // 4. BUILD RESPONSE
            Map<String, Object> res = new HashMap<>();
            res.put("success", true);
            res.put("orderId", result.orderId);           // Cashfree order_id (e.g. ORD_67)
            res.put("dbOrderId", order.getId());          // Your DB ID (for polling)
            res.put("amount", total);
            res.put("paymentSessionId", result.paymentSessionId);
            res.put("paymentLink", result.paymentLink);   // DIRECT WORKING LINK
            res.put("qrCodeUrl", result.qrCodeUrl);       // For UPI QR

            log.info("Payment Created → DB ID: {} | Cashfree ID: {} | Link: {}", 
                order.getId(), result.orderId, result.paymentLink);

            return ResponseEntity.ok(res);

        } catch (Exception e) {
            log.error("Payment creation failed", e);
            return ResponseEntity.status(500).body(Map.of("error", e.getMessage()));
        }
    }


 @GetMapping("/order-status/{id}")
    public ResponseEntity<Map<String, Object>> getOrderStatus(@PathVariable String id, Authentication auth) {
        try {
            Long orderId;
            if (id.startsWith("ORD_")) {
                orderId = Long.parseLong(id.replaceAll("\\D", ""));  // Extract number
            } else {
                orderId = Long.parseLong(id);
            }

            Orders order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new RuntimeException("Order not found"));

            Map<String, Object> res = new HashMap<>();
            res.put("status", order.getStatus());
            res.put("transactionId", order.getTransactionId() != null ? order.getTransactionId() : "");
            return ResponseEntity.ok(res);

        } catch (Exception e) {
            Map<String, Object> res = new HashMap<>();
            res.put("status", "PENDING");
            return ResponseEntity.ok(res);
        }
    }
}
