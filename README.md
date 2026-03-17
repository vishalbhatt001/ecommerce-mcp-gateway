# E-commerce MCP Gateway (Local Only)

Production-hardened local MCP server for order resolution.

Full documentation:
- `docs/COMPLETE_APPLICATION_AND_API_GUIDE.md`
- `docs/CLASS_METHOD_LIBRARY_EXPLAINED.md`

## Prerequisites

- Java 21
- Maven 3.9+
- Ollama running locally
- Models:
  - `phi3`
  - `nomic-embed-text`

## Start Ollama

```bash
ollama serve
```

Pull models once:

```bash
ollama pull phi3
ollama pull nomic-embed-text
```

## Run the project

```bash
mvn spring-boot:run
```

Server starts on `http://localhost:8080`.

## Swagger / OpenAPI

- Swagger UI: `http://localhost:8080/swagger-ui.html`
- OpenAPI JSON: `http://localhost:8080/v3/api-docs`

## MCP Endpoints

- SSE stream: `POST /mcp/sse`
- JSON-RPC message: `POST /mcp/message`

## Day-0 Seed Data

The app auto-seeds H2 at startup with orders:

- `10001` PROCESSING
- `10002` SHIPPED
- `10003` DELIVERED
- `10004` PAYMENT_FAILED
- `10005` CANCELLED

## Test Commands

### 1) Subscribe to SSE (includes heartbeat every 15s)

```bash
curl -N -X POST http://localhost:8080/mcp/sse
```

### 2) List tools

```bash
curl -s -X POST http://localhost:8080/mcp/message \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":"1",
    "method":"tools/list",
    "params":{}
  }'
```

Expected shape:

```json
{
  "jsonrpc": "2.0",
  "id": "1",
  "result": {
    "tools": [
      {
        "name": "resolve_order",
        "description": "Resolve order issues by orderId"
      }
    ]
  }
}
```

### 3) Resolve order

```bash
curl -s -X POST http://localhost:8080/mcp/message \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":"2",
    "method":"tools/call",
    "params":{"orderId":"10002"}
  }'
```

Expected shape:

```json
{
  "jsonrpc": "2.0",
  "id": "2",
  "result": {
    "orderId": "10002",
    "status": "SHIPPED",
    "policy": "SHIPPED: ...",
    "resolution": "..."
  }
}
```

### 4) Invalid orderId validation

```bash
curl -s -X POST http://localhost:8080/mcp/message \
  -H "Content-Type: application/json" \
  -d '{
    "jsonrpc":"2.0",
    "id":"3",
    "method":"tools/call",
    "params":{"orderId":"ABC"}
  }'
```

Expected shape:

```json
{
  "jsonrpc": "2.0",
  "id": "3",
  "error": {
    "code": -32602,
    "message": "Invalid params: orderId must match ^[0-9]{5}$"
  }
}
```

## Notes

- LLM execution is local-only (Ollama on `localhost`).
- Inference concurrency is throttled to 1 via semaphore.
- Circuit breaker protects `/mcp/message` and returns JSON-RPC error objects on failure.
