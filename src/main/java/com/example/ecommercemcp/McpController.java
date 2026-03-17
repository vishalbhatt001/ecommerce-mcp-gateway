package com.example.ecommercemcp;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import io.github.resilience4j.circuitbreaker.annotation.CircuitBreaker;
import jakarta.validation.Valid;
import jakarta.validation.constraints.Pattern;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.Semaphore;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.http.codec.ServerSentEvent;
import org.springframework.http.server.reactive.ServerHttpRequest;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.publisher.Sinks;
import reactor.core.scheduler.Schedulers;

@RestController
@RequestMapping("/mcp")
@Validated
@Slf4j
@RequiredArgsConstructor
@Tag(name = "MCP", description = "JSON-RPC 2.0 endpoints for local MCP communication")
public class McpController {

    private final OrderDatabaseTool orderDatabaseTool;
    private final PolicyRagTool policyRagTool;
    private final AiConfig.OrderResolutionAssistant assistant;
    private final Semaphore llmSemaphore;
    private final ObjectMapper objectMapper;
    private final Sinks.Many<String> outbound = Sinks.many().multicast().directBestEffort();

    @GetMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent events stream", description = "Streams JSON-RPC responses and 15s heartbeat comments.")
    public Flux<ServerSentEvent<String>> sse(ServerHttpRequest request) {
        String endpointUrl = request.getURI().resolve("/mcp/message").toString();
        Flux<ServerSentEvent<String>> endpointEvent = Flux.just(
                ServerSentEvent.<String>builder()
                        .event("endpoint")
                        .data(endpointUrl)
                        .build()
        );

        Flux<ServerSentEvent<String>> heartbeat = Flux.interval(Duration.ofSeconds(15))
                .map(tick -> ServerSentEvent.<String>builder().comment("").build());

        Flux<ServerSentEvent<String>> dataEvents = outbound.asFlux()
                .map(json -> ServerSentEvent.<String>builder()
                        .event("message")
                        .data(json)
                        .build());

        return Flux.merge(endpointEvent, heartbeat, dataEvents);
    }

    @PostMapping(value = "/sse", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    @Operation(summary = "Server-sent events stream (legacy POST)", description = "Backward-compatible POST stream with endpoint event and heartbeat.")
    public Flux<ServerSentEvent<String>> ssePost(ServerHttpRequest request) {
        return sse(request);
    }

    @PostMapping(value = "/message", consumes = MediaType.APPLICATION_JSON_VALUE, produces = MediaType.APPLICATION_JSON_VALUE)
    @CircuitBreaker(name = "mcp", fallbackMethod = "messageFallback")
    @Operation(summary = "Handle MCP JSON-RPC request", description = "Processes JSON-RPC 2.0 messages and returns strict JSON-RPC responses.")
    public Mono<ResponseEntity<?>> message(@Valid @RequestBody JsonRpcRequest request) {
        return Mono.fromCallable(() -> handleJsonRpc(request))
                .subscribeOn(Schedulers.boundedElastic())
                .map(envelope -> {
                    if (envelope == null) {
                        return ResponseEntity.noContent().build();
                    }
                    emitToSse(envelope);
                    return ResponseEntity.ok(envelope.toMap());
                });
    }

    private JsonRpcEnvelope handleJsonRpc(JsonRpcRequest request) {
        if (!"2.0".equals(request.jsonrpc())) {
            return JsonRpcEnvelope.error(request.id(), -32600, "Invalid Request: jsonrpc must be '2.0'", null);
        }
        if (request.method() == null || request.method().isBlank()) {
            return JsonRpcEnvelope.error(request.id(), -32600, "Invalid Request: method is required", null);
        }
        if ("notifications/initialized".equals(request.method())) {
            return null;
        }
        if (request.id() == null) {
            return JsonRpcEnvelope.error(null, -32600, "Invalid Request: id is required", null);
        }

        return switch (request.method()) {
            case "initialize" -> JsonRpcEnvelope.result(request.id(), Map.of(
                    "protocolVersion", "2024-11-05",
                    "capabilities", Map.of(
                            "tools", Map.of("listChanged", false)
                    ),
                    "serverInfo", Map.of(
                            "name", "order-resolution-local",
                            "version", "1.0.0"
                    )
            ));
            case "tools/list" -> JsonRpcEnvelope.result(request.id(), Map.of(
                    "tools", new Object[]{
                            Map.of(
                                    "name", "resolve_order",
                                    "description", "Resolve order issues by orderId",
                                    "inputSchema", Map.of(
                                            "type", "object",
                                            "properties", Map.of(
                                                    "orderId", Map.of(
                                                            "type", "string",
                                                            "pattern", "^[0-9]{5}$",
                                                            "description", "5-digit order ID"
                                                    )
                                            ),
                                            "required", new String[]{"orderId"},
                                            "additionalProperties", false
                                    )
                            )
                    }
            ));
            case "tools/call" -> executeToolCall(request);
            case "ping" -> JsonRpcEnvelope.result(request.id(), Map.of());
            default -> JsonRpcEnvelope.error(request.id(), -32601, "Method not found", Map.of("method", request.method()));
        };
    }

    private JsonRpcEnvelope executeToolCall(JsonRpcRequest request) {
        Map<String, Object> params = request.params();
        String toolName = params != null && params.get("name") != null ? String.valueOf(params.get("name")) : "resolve_order";
        Map<String, Object> arguments = params != null && params.get("arguments") instanceof Map<?, ?> args
                ? (Map<String, Object>) args
                : Map.of();

        String orderId = arguments.get("orderId") != null
                ? String.valueOf(arguments.get("orderId"))
                : (params != null && params.get("orderId") != null ? String.valueOf(params.get("orderId")) : null);

        if (!"resolve_order".equals(toolName)) {
            return JsonRpcEnvelope.error(request.id(), -32602, "Invalid params: unknown tool", Map.of("name", toolName));
        }
        return executeResolveOrder(request.id(), orderId);
    }

    private JsonRpcEnvelope executeResolveOrder(Object requestId, String orderId) {
        if (orderId == null || !orderId.matches("^[0-9]{5}$")) {
            return JsonRpcEnvelope.error(requestId, -32602, "Invalid params: orderId must match ^[0-9]{5}$", null);
        }

        ResolutionState state = new ResolutionState(requestId, orderId);
        transition(state, "VALIDATE_INPUT");

        var order = orderDatabaseTool.getOrderById(orderId);
        transition(state, "LOOKUP_ORDER");
        if (order.isEmpty()) {
            return JsonRpcEnvelope.error(requestId, 40401, "Order not found", Map.of("orderId", orderId));
        }
        state.status = order.get().status();

        state.policy = policyRagTool.resolvePolicyForStatus(state.status);
        transition(state, "POLICY_RAG");

        String prompt = "Order ID: " + order.get().orderId()
                + ", Customer: " + order.get().customerName()
                + ", Status: " + order.get().status()
                + ", Policy: " + state.policy
                + ". Return a concise resolution.";
        boolean acquired = false;
        try {
            llmSemaphore.acquire();
            acquired = true;
            transition(state, "LLM_INFERENCE");
            state.finalAnswer = assistant.answer(prompt);
        } catch (InterruptedException ex) {
            Thread.currentThread().interrupt();
            return JsonRpcEnvelope.error(requestId, 50002, "Inference interrupted", null);
        } catch (Exception ex) {
            return JsonRpcEnvelope.error(requestId, 50003, "Inference failure", Map.of("message", ex.getMessage()));
        } finally {
            if (acquired) {
                llmSemaphore.release();
            }
        }

        transition(state, "DONE");
        return JsonRpcEnvelope.result(requestId, Map.of(
                "content", new Object[]{
                        Map.of(
                                "type", "text",
                                "text", state.finalAnswer
                        )
                },
                "structuredContent", Map.of(
                        "orderId", order.get().orderId(),
                        "status", order.get().status(),
                        "policy", state.policy,
                        "resolution", state.finalAnswer
                )
        ));
    }

    public Mono<ResponseEntity<?>> messageFallback(JsonRpcRequest request, Throwable throwable) {
        JsonRpcEnvelope envelope = JsonRpcEnvelope.error(
                request == null ? null : request.id(),
                50001,
                "Service unavailable",
                Map.of("reason", throwable.getMessage())
        );
        emitToSse(envelope);
        return Mono.just(ResponseEntity.ok(envelope.toMap()));
    }

    private void emitToSse(JsonRpcEnvelope envelope) {
        try {
            outbound.tryEmitNext(objectMapper.writeValueAsString(envelope.toMap()));
        } catch (JsonProcessingException e) {
            log.error("Failed to serialize JSON-RPC envelope for SSE", e);
        }
    }

    private void transition(ResolutionState state, String nextNode) {
        log.info("LangGraph state transition: {} -> {}", state.node, nextNode);
        state.node = nextNode;
    }

    private static final class ResolutionState {
        private final Object requestId;
        @Pattern(regexp = "^[0-9]{5}$")
        private final String orderId;
        private String node = "START";
        private String status;
        private String policy;
        private String finalAnswer;

        private ResolutionState(Object requestId, String orderId) {
            this.requestId = requestId;
            this.orderId = orderId;
        }
    }

    public record JsonRpcRequest(
            String jsonrpc,
            Object id,
            String method,
            Map<String, Object> params
    ) {
    }

    private record JsonRpcEnvelope(
            String jsonrpc,
            Object id,
            Object result,
            JsonRpcError error
    ) {
        static JsonRpcEnvelope result(Object id, Object result) {
            return new JsonRpcEnvelope("2.0", id, result, null);
        }

        static JsonRpcEnvelope error(Object id, int code, String message, Object data) {
            return new JsonRpcEnvelope("2.0", id, null, new JsonRpcError(code, message, data));
        }

        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("jsonrpc", jsonrpc);
            map.put("id", id);
            if (error == null) {
                map.put("result", result);
            } else {
                map.put("error", error.toMap());
            }
            return map;
        }
    }

    private record JsonRpcError(int code, String message, Object data) {
        Map<String, Object> toMap() {
            Map<String, Object> map = new LinkedHashMap<>();
            map.put("code", code);
            map.put("message", message);
            if (data != null) {
                map.put("data", data);
            }
            return map;
        }
    }
}
