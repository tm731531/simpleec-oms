# REST API

> Defines URL structure, authentication, response format, HTTP method semantics, error codes, controller layering, Channel Job isolation, validation, and security for the SimpleEC OMS REST API. Read this before writing any controller or making any API call.

---

## 1. URL Structure

**All endpoints must be prefixed with `/api`.**

This is not optional. Missing the `/api` prefix causes Spring Security to redirect the request to `/error`, which then returns 403 — a confusing and hard-to-debug failure. This exact bug has occurred in production (Feb 2026 outage, affected all 11 controllers).

```java
// CORRECT
@RequestMapping("/api/user/orders")
@RequestMapping("/api/admin/merchants")
@RequestMapping("/api/auth")

// WRONG — missing /api prefix
@RequestMapping("/user/orders")
@RequestMapping("/admin/merchants")
```

**URL hierarchy:**
```
/api/auth/**                  — public: login, logout, me
/api/admin/**                 — platform admin only (ROLE_PLATFORM_ADMIN)
/api/admin/auth/**            — public: admin login
/api/user/**                  — merchant operators (authenticated)
/api/health                   — public: health check
/api/version                  — public: version info
/api/enums/**                 — public: enum values for frontend dropdowns
/api/user/channels/platforms  — public: available platform list
```

**Resource naming:** Use plural nouns for collections (`/api/user/orders`, `/api/user/sell-packs`). Use kebab-case for multi-word resources (`/api/user/sell-packs`, `/api/user/shipment-batches`).

---

## 2. Authentication

**All non-public endpoints require `Authorization: Bearer {token}`.**

Authentication is JWT-based, stateless (no server-side session). `JwtAuthFilter` runs before every request.

**Token acquisition:**
```http
POST /api/auth/login
Content-Type: application/json

{
  "email": "user@example.com",
  "password": "..."
}
```

Response:
```json
{
  "token": "eyJhbGc...",
  "user": {
    "id": "accountNanoId",
    "email": "user@example.com",
    "name": "Alice",
    "merchantId": "merchantNanoId",
    "merchantName": "Example Co",
    "role": "main"
  }
}
```

**Token usage:**
```http
GET /api/user/orders
Authorization: Bearer eyJhbGc...
```

**Token structure (JWT claims):**
| Claim | Value |
|---|---|
| `sub` | `accountId` (NanoID-20) |
| `merchantId` | Merchant NanoID-20 |
| `email` | Account email |
| `name` | Account name |
| `role` | `main` or `sub` |

**Token lifetime:** Configured via `jwt.expiration` (seconds). Default varies by environment.

**Accessing the principal in a controller:**
```java
@GetMapping("/{id}")
public ResponseEntity<Order> getOrder(
        @AuthenticationPrincipal UserPrincipal principal,
        @PathVariable String id) {
    // principal.getMerchantId() — always use this for tenant isolation
    // principal.getAccountId()
    // principal.getEmail()
    // principal.getRole()
}
```

**Public endpoints (no token required):**
- `POST /api/auth/login`
- `GET /api/auth/me` (returns 401 if unauthenticated, not an error)
- `POST /api/auth/logout`
- `POST /api/admin/auth/login`
- `GET /api/health`
- `GET /api/version`
- `GET /api/enums/**`
- `GET /api/user/channels/platforms`
- `/actuator/**`

Defined in `SecurityConfig.requestMatchers(...).permitAll()`.

---

## 3. Response Format

**Two response envelope types are used depending on controller type.**

### 3.1 Admin controllers — `AdminApiResponse<T>`

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

Error:
```json
{
  "code": 404,
  "message": "Merchant not found: xyz",
  "data": null
}
```

Factory methods on `AdminApiResponse<T>`:
```java
AdminApiResponse.success(data)           // 200 + data
AdminApiResponse.success()               // 200, no data
AdminApiResponse.badRequest("message")   // 400
AdminApiResponse.notFound("message")     // 404
AdminApiResponse.conflict("message")     // 409
AdminApiResponse.serverError("message")  // 500
AdminApiResponse.error(code, "message")  // custom
```

### 3.2 User controllers — inline maps or typed VOs

User-facing controllers return `ResponseEntity<T>` with typed objects or `Map.of(...)` for simple responses. There is no shared user envelope class — use typed response objects (`OrderVO`, `UserPageResponse<T>`) directly.

**Paginated responses** use `UserPageResponse<T>`:
```json
{
  "content": [ ... ],
  "totalElements": 123,
  "totalPages": 13,
  "currentPage": 1,
  "pageSize": 10
}
```

**Simple accepted/error responses:**
```json
{ "channelOrderId": "...", "status": "accepted" }
{ "error": "channelId and channelOrderId are required" }
```

**Rule:** Never return naked Java entity objects (e.g., `Order`) from user-facing endpoints that contain PII. Always project to a VO that omits or masks sensitive fields, except where PII is legitimately needed by the operator.

---

## 4. HTTP Methods

| Method | Semantics | Idempotent |
|---|---|---|
| `GET` | Read only. No side effects. | Yes |
| `POST` | Create a new resource, or trigger an action (e.g., publish to Kafka). | No |
| `PUT` | Full replacement of a resource. | Yes |
| `PATCH` | Partial update of a resource. | No (use request body `action` field for state transitions) |
| `DELETE` | Remove a resource. | Yes |

**State transitions use `PATCH` with an `action` field:**
```http
PATCH /api/user/orders/{id}
{
  "action": "ship",
  "trackingNumber": "...",
  "carrier": "..."
}
```
Actions: `ship`, `cancel`. Invalid `action` values → 400.

**Accepted responses for async operations:** When an action publishes to Kafka and returns before the result is known, return `202 Accepted` (not `200 OK`):
```java
return ResponseEntity.accepted().body(Map.of("status", "accepted", ...));
```

---

## 5. Error Status Codes

| HTTP Status | When to use |
|---|---|
| `200 OK` | Successful synchronous operation. |
| `202 Accepted` | Request received and queued (Kafka publish). Processing not yet complete. |
| `400 Bad Request` | Missing required fields, invalid field values, unknown `action`. |
| `401 Unauthorized` | No token, expired token, or invalid token. |
| `403 Forbidden` | Token valid, but insufficient permission (wrong role, or resource belongs to different merchant). |
| `404 Not Found` | Resource does not exist, or exists but belongs to a different merchant (treat as 404 to avoid enumeration). |
| `409 Conflict` | Business rule conflict: duplicate email, duplicate channel order, etc. |
| `500 Internal Server Error` | Unexpected exception. Log the stack trace; return a safe error message without internal details. |

**Tenant isolation rule:** If a resource exists but belongs to a different merchant, return `404` (not `403`). Do not reveal that the resource exists to another tenant.

```java
// CORRECT
Optional<Order> order = orderRepository.findById(id);
if (order.isEmpty() || !principal.getMerchantId().equals(order.get().getMerchantId())) {
    return ResponseEntity.notFound().build();  // 404 in both cases
}
```

---

## 6. Controller Layering

**Strict three-layer rule: Controller → Service → Repository.**

Controllers must not call repositories directly for business logic queries. This rule exists so that business logic (encryption context, transaction boundaries, caching) lives in the Service layer, not scattered across controllers.

```
CORRECT:
  UserOrderController → OrderService → OrderRepository

WRONG (violates layering):
  UserOrderController → OrderRepository (direct)
```

**Exception — simple lookup for validation within the same request:** A controller may directly call a repository to validate ownership (e.g., "does this channelId belong to this merchant?") before building a Kafka message, because this is a controller-level concern, not business logic. See `UserOrderController.receiveOrder()` for this pattern.

**Controllers are responsible for:**
- Extracting and validating `@AuthenticationPrincipal`
- Input validation (required fields, format checks)
- Building Kafka message envelope (header + body)
- Returning appropriate HTTP status and response body

**Services are responsible for:**
- Transaction management (`@Transactional`)
- Setting and clearing `EncryptionContext` (for PII fields)
- Calling repositories
- Business rule enforcement

**Repositories are responsible for:**
- SQL queries only. No business logic.

---

## 7. Channel Job Isolation

**Channel Job must NOT call REST APIs or access the database directly.**

Channel Job's only communication channel is Kafka. It consumes from platform topics and produces to `order.process`, `return.process`, `{platform}.fast`, or `{platform}.slow`.

```
CORRECT: Channel Job → Kafka message → Order Job → DB
WRONG:   Channel Job → REST API → Controller → DB
WRONG:   Channel Job → JPA Repository → DB
```

If Channel Job needs data from the DB (e.g., to look up `channelOrderId` for an outbound action), it is a sign that the architecture is wrong — the Kafka message body should already contain the NanoID, and the Channel Job should not need to query anything. Revisit the message design.

This isolation ensures that Channel Job can scale independently, fail independently, and be replaced per-platform without touching the core OMS.

---

## 8. API Versioning

**Current:** No version prefix. All endpoints are under `/api`.

**Future breaking changes:** When a breaking change is needed, introduce `/api/v2/...` endpoints alongside the existing `/api/...` endpoints. Do not remove the old endpoints until all clients have migrated.

**Non-breaking additions** (new fields in response, new optional query parameters) do not require a version bump.

**Current controllers (all under `/api`):**

| Controller | Prefix | Auth Required |
|---|---|---|
| `AuthController` | `/api/auth` | No (public) |
| `AdminAuthController` | `/api/admin/auth` | No (public) |
| `AdminController` | `/api/admin` | ROLE_PLATFORM_ADMIN |
| `AdminAccountController` | `/api/admin/accounts` | ROLE_PLATFORM_ADMIN |
| `AdminMerchantController` | `/api/admin/merchants` | ROLE_PLATFORM_ADMIN |
| `AdminPlatformController` | `/api/admin/platforms` | ROLE_PLATFORM_ADMIN |
| `UserOrderController` | `/api/user/orders` | Authenticated |
| `UserChannelController` | `/api/user/channels` | Authenticated |
| `UserSellPackController` | `/api/user/sell-packs` | Authenticated |
| `UserInventoryController` | `/api/user/inventory` | Authenticated |
| `UserShipmentController` | `/api/user/shipments` | Authenticated |
| `UserShipmentBatchController` | `/api/user/shipment-batches` | Authenticated |
| `UserRefundController` | `/api/user/refunds` | Authenticated |
| `UserStatsController` | `/api/user/stats` | Authenticated |
| `UserSettingsController` | `/api/user/settings` | Authenticated |
| `UserReportController` | `/api/user/reports` | Authenticated |
| `UserProductController` | `/api/user/products` | Authenticated |
| `ProductController` | `/api/products` | Authenticated |
| `OrderController` | `/api/orders` | Authenticated |
| `ReturnController` | `/api/returns` | Authenticated |
| `HealthController` | `/api/health`, `/api/version` | No (public) |
| `EnumController` | `/api/enums` | No (public) |

---

## 9. Request Validation

**Use `@Valid` on `@RequestBody` parameters for standard bean validation.**

```java
@PostMapping
public ResponseEntity<?> createSomething(
        @AuthenticationPrincipal UserPrincipal principal,
        @Valid @RequestBody CreateSomethingRequest request) { ... }
```

**Required field checks in controller body** (for `Map<String, Object>` request bodies that don't use a typed DTO):
```java
String channelId = (String) requestBody.get("channelId");
if (channelId == null || channelId.isBlank()) {
    return ResponseEntity.badRequest()
            .body(Map.of("error", "channelId is required"));
}
```

**Business validation rules** (ownership, existence, business state) go in the Service layer, not in the controller.

**Common validations that every user controller must perform:**
1. Verify the resource's `merchantId` matches `principal.getMerchantId()` before returning or modifying it.
2. Reject requests where required fields are null or blank with `400 Bad Request`.
3. Return `404` (not `403`) when a resource doesn't belong to the authenticated merchant.

**`merchantId` must always come from the JWT — never from request parameters.**

Accepting `merchantId` as a `@RequestParam` or in a request body field that the caller can control is a tenant-isolation vulnerability. Every user-facing endpoint derives the merchant scope exclusively from `principal.getMerchantId()`:

```java
// CORRECT — merchantId is always from the JWT principal
@GetMapping
public ResponseEntity<Page<Product>> listProducts(
        @AuthenticationPrincipal UserPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "10") int size) {
    String merchantId = principal.getMerchantId();  // ← always from token
    ...
}

// CORRECT — on create, forcibly set merchantId from principal; ignore any value in the body
@PostMapping
public ResponseEntity<Product> createProduct(
        @AuthenticationPrincipal UserPrincipal principal,
        @RequestBody Product product) {
    product.setMerchantId(principal.getMerchantId());  // ← overrides anything the caller sent
    ...
}

// WRONG — never accept merchantId from the caller
@GetMapping
public ResponseEntity<?> listProducts(
        @RequestParam String merchantId,  // ← security hole: caller can specify any merchant
        ...) { ... }
```

**Merchants cannot access or modify each other's products (or any other resource).** For single-resource endpoints (`GET /{id}`, `PATCH /{id}`, `DELETE /{id}`), always verify ownership before proceeding:

```java
Optional<Product> product = productService.findById(productId);
if (product.isEmpty() || !principal.getMerchantId().equals(product.get().getMerchantId())) {
    return ResponseEntity.notFound().build();  // 404 in both cases — do not reveal existence
}
```

This pattern applies to all resource types: products, orders, sell-packs, channels, refunds, shipments, etc.

---

## 10. Security

### 10.1 CORS

CORS is configured in `SecurityConfig.corsConfigurationSource()`:

| Setting | Value |
|---|---|
| Allowed origins | `simpleec.cors.allowed-origins` property. Defaults: `http://localhost:8080,http://localhost:5173,http://localhost:3000` |
| Allowed methods | `GET, POST, PUT, PATCH, DELETE, OPTIONS` |
| Allowed headers | `Content-Type, Authorization, Accept, X-Requested-With` |
| Allow credentials | `true` |

In production, `simpleec.cors.allowed-origins` must be set explicitly to the frontend's actual URL. Leaving the default in production is a security misconfiguration.

### 10.2 Admin vs user endpoint separation

Admin endpoints require `ROLE_PLATFORM_ADMIN` authority. This authority is set in `JwtAuthFilter` based on the `role` claim in the JWT. Platform admin tokens are issued by `AdminAuthController` (separate login flow).

```
/api/admin/**  → hasAuthority("ROLE_PLATFORM_ADMIN")
/api/user/**   → authenticated() (any valid merchant JWT)
```

**Never mix admin and user logic in the same controller.** Admin controllers (`AdminController`, `AdminMerchantController`, etc.) operate on behalf of the platform operator. User controllers (`UserOrderController`, etc.) operate on behalf of a merchant within their tenant scope.

### 10.3 JWT configuration

```yaml
jwt:
  secret: <minimum 32-char random string>
  expiration: 86400  # seconds (24h default)
```

`JwtUtil` uses HMAC-SHA256 via `Keys.hmacShaKeyFor(secret.getBytes())`. The secret must be at least 256 bits (32 bytes). Do not use short secrets in any environment.

### 10.4 Session policy

The API is fully stateless (`SessionCreationPolicy.STATELESS`). No HTTP sessions are created or used. Token revocation requires either short expiration or a token blocklist (not currently implemented — use short expiration as mitigation).

### 10.5 CSRF

CSRF protection is disabled (`.csrf(c -> c.disable())`). This is correct for stateless JWT APIs where browsers do not send cookies. If cookie-based auth is ever added, CSRF protection must be re-enabled.
