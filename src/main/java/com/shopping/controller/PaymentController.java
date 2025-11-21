package com.shopping.controller;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.shopping.entity.Orders;
import com.shopping.entity.User;
import com.shopping.repository.OrderRepository;
import com.shopping.repository.UserRepository;
import com.shopping.service.CashfreeService;
import org.springframework.http.HttpMethod;
import jakarta.servlet.http.HttpServletRequest;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.client.RestTemplate;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import java.util.HashMap;
import java.util.LinkedHashMap;
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

  @PostMapping("/create-payment-session")
public ResponseEntity<?> createPaymentSession(
        @RequestBody Map<String, Object> request,
        Authentication authentication) {

    String email = authentication.getName();
    User user = userRepository.findByEmail(email)
            .orElseThrow(() -> new RuntimeException("User not found"));

    // Generate unique order ID
    String orderId = "ORD_" + System.currentTimeMillis();
    Double amount = Double.valueOf(request.get("amount").toString());

    // Cashfree payload
    Map<String, Object> payload = new HashMap<>();
    payload.put("order_id", orderId);
    payload.put("order_amount", amount);
    payload.put("order_currency", "INR");
    payload.put("customer_details", Map.of(
            "customer_id", "user_" + user.getId(),
            "customer_name", user.getName(),
            "customer_email", user.getEmail(),
            "customer_phone", user.getPhone()
    ));
    payload.put("order_meta", Map.of(
            "return_url", "https://jayshopfinal2.onrender.com/payment-success?order_id=" + orderId
    ));

    // Headers
    HttpHeaders headers = new HttpHeaders();
    headers.set("x-client-id", "TEST1234567890");           // ← Ne App ID
    headers.set("x-client-secret", "cfsk_test_abc123..."); // ← Ne Secret
    headers.set("x-api-version", "2023-08-01");
    headers.setContentType(MediaType.APPLICATION_JSON);

    HttpEntity<Map<String, Object>> entity = new HttpEntity<>(payload, headers);

    RestTemplate restTemplate = new RestTemplate();

    try {
        // CORRECT WAY to call with ParameterizedTypeReference
        ResponseEntity<Map<String, Object>> response = restTemplate.exchange(
                "https://api.cashfree.com/pg/orders",
                HttpMethod.POST,
                entity,
                new ParameterizedTypeReference<Map<String, Object>>() {}
        );

        Map<String, Object> responseBody = response.getBody();

        // 2025 lo payment_session_id ila vasthundi → { "id": "session_xxx" }
        Map<String, Object> sessionObj = (Map<String, Object>) responseBody.get("payment_session_id");
        String paymentSessionId = (String) sessionObj.get("id");

        // Save order in DB
        Orders order = new Orders();
        order.setCashfreeOrderId(orderId);
        order.setTotal(amount);
        order.setStatus("PENDING");
        order.setUser(user);
        orderRepository.save(order);

        // Return to frontend
        Map<String, String> result = new HashMap<>();
        result.put("payment_session_id", paymentSessionId);
        result.put("order_id", orderId);

        return ResponseEntity.ok(result);

    } catch (Exception e) {
        log.error("Cashfree payment session failed", e);
        return ResponseEntity.status(500)
                .body(Map.of("error", "Payment initiation failed: " + e.getMessage()));
    }
}
}
