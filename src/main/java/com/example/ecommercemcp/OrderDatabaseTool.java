package com.example.ecommercemcp;

import jakarta.annotation.PostConstruct;
import jakarta.validation.constraints.Pattern;
import java.math.BigDecimal;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;
import org.springframework.validation.annotation.Validated;

@Component
@Validated
@Slf4j
@RequiredArgsConstructor
public class OrderDatabaseTool {

    private static final java.util.regex.Pattern ORDER_ID_REGEX = java.util.regex.Pattern.compile("^[0-9]{5}$");
    private final JdbcTemplate jdbcTemplate;

    @PostConstruct
    public void initializeAndSeed() {
        jdbcTemplate.execute("""
                CREATE TABLE IF NOT EXISTS orders (
                    order_id VARCHAR(5) PRIMARY KEY,
                    customer_name VARCHAR(255) NOT NULL,
                    status VARCHAR(64) NOT NULL,
                    total DECIMAL(12,2) NOT NULL,
                    updated_at TIMESTAMP WITH TIME ZONE NOT NULL
                )
                """);

        jdbcTemplate.batchUpdate("""
                MERGE INTO orders (order_id, customer_name, status, total, updated_at)
                KEY(order_id)
                VALUES (?, ?, ?, ?, ?)
                """, List.of(
                new Object[]{"10001", "Ava Carter", "PROCESSING", new BigDecimal("129.99"), OffsetDateTime.now().minusHours(5)},
                new Object[]{"10002", "Noah Patel", "SHIPPED", new BigDecimal("49.50"), OffsetDateTime.now().minusDays(1)},
                new Object[]{"10003", "Mia Nguyen", "DELIVERED", new BigDecimal("215.00"), OffsetDateTime.now().minusDays(3)},
                new Object[]{"10004", "Liam Chen", "PAYMENT_FAILED", new BigDecimal("89.00"), OffsetDateTime.now().minusMinutes(40)},
                new Object[]{"10005", "Emma Brooks", "CANCELLED", new BigDecimal("19.99"), OffsetDateTime.now().minusDays(2)}
        ));

        log.info("OrderDatabaseTool upserted 5 default orders for Day-0 testing");
    }

    public Optional<OrderRecord> getOrderById(@Pattern(regexp = "^[0-9]{5}$") String orderId) {
        if (orderId == null || !ORDER_ID_REGEX.matcher(orderId).matches()) {
            throw new IllegalArgumentException("orderId must match ^[0-9]{5}$");
        }
        List<OrderRecord> rows = jdbcTemplate.query("""
                        SELECT order_id, customer_name, status, total, updated_at
                        FROM orders
                        WHERE order_id = ?
                        """,
                this::mapOrder,
                orderId);
        return rows.stream().findFirst();
    }

    private OrderRecord mapOrder(ResultSet rs, int rowNum) throws SQLException {
        return new OrderRecord(
                rs.getString("order_id"),
                rs.getString("customer_name"),
                rs.getString("status"),
                rs.getBigDecimal("total"),
                rs.getObject("updated_at", OffsetDateTime.class)
        );
    }

    public record OrderRecord(
            String orderId,
            String customerName,
            String status,
            BigDecimal total,
            OffsetDateTime updatedAt
    ) {
    }
}
