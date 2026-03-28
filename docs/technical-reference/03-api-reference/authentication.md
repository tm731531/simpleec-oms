# Authentication & Authorization

SimpleEC OMS uses stateless JWT (JSON Web Token) authentication. There are no server-side sessions. Every protected request must carry a valid Bearer token in the `Authorization` header.

---

## How It Works

```
1. Client sends credentials (email + password) to /api/auth/login
2. Server validates credentials against the accounts table
3. Server signs a JWT containing identity claims and returns it
4. Client stores the token (localStorage / memory)
5. Client sends the token on every subsequent request:
       Authorization: Bearer <token>
6. JwtAuthFilter intercepts each request, verifies the signature,
   and injects a UserPrincipal into the Spring Security context
7. Controllers receive the principal via @AuthenticationPrincipal UserPrincipal
```

There is no refresh token mechanism. When the token expires the client must re-login.

---

## Token Payload (Claims)

The JWT is signed with HMAC-SHA (key length >= 256 bits). The payload contains:

| Claim        | Type   | Description                                      |
|--------------|--------|--------------------------------------------------|
| `sub`        | String | Account ID (NanoID, 20 chars)                    |
| `merchantId` | String | Merchant the account belongs to (6-char NanoID)  |
| `email`      | String | Account email address                            |
| `name`       | String | Account display name                             |
| `role`       | String | `main`, `sub`, or `platform_admin`               |
| `iat`        | Number | Issued-at timestamp (Unix seconds)               |
| `exp`        | Number | Expiry timestamp (Unix seconds)                  |

### Sample Decoded Payload

```json
{
  "sub": "V4nKq8mR2xLpYoZw1A3B",
  "merchantId": "a00000",
  "email": "owner@acme-shop.com",
  "name": "Alice Chen",
  "role": "main",
  "iat": 1743120000,
  "exp": 1743724800
}
```

### Token Expiry

Tokens are valid for **7 days (604800 seconds)** from the time of issue. The expiry is configured in `application.yml` via `jwt.expiration`.

---

## Roles

| Role             | Description                                                                 |
|------------------|-----------------------------------------------------------------------------|
| `main`           | Main account for a merchant. Full access to all resources of that merchant. |
| `sub`            | Sub-account under a merchant. Same merchant scope; typically limited UI.   |
| `platform_admin` | Platform-level administrator. Can manage merchants, accounts, platforms.   |

### Spring Security Authorities

| Role             | GrantedAuthority          |
|------------------|---------------------------|
| `main`           | `ROLE_MERCHANT_MAIN`, `ROLE_USER` |
| `sub`            | `ROLE_USER`               |
| `platform_admin` | `ROLE_PLATFORM_ADMIN`, `ROLE_USER` |

All `/api/admin/**` endpoints require `ROLE_PLATFORM_ADMIN`. Regular user endpoints require any authenticated principal.

---

## Merchant Isolation

Every controller that returns merchant-scoped data reads `merchantId` exclusively from the JWT principal — never from the request body or query parameters. This is enforced by `@AuthenticationPrincipal UserPrincipal principal`.

```java
// Example: UserOrderController.listOrders
orders = orderRepository.findByMerchantId(principal.getMerchantId(), pageable);
```

A user from merchant `a00001` can never see merchant `a00002`'s data even if they guess the ID, because the query always filters by the JWT's `merchantId`. If a resource (e.g. an order) exists but belongs to a different merchant, the API returns 404 (not 403) to avoid leaking the resource's existence.

---

## Public Endpoints (No Authentication Required)

The following paths are explicitly permitted without a token:

| Path                              | Description                           |
|-----------------------------------|---------------------------------------|
| `POST /api/auth/login`            | User login                            |
| `GET /api/auth/me` (*)            | Returns 401 if not authenticated      |
| `POST /api/auth/logout`           | Stateless — always returns 200        |
| `POST /api/admin/auth/login`      | Platform admin login                  |
| `GET /api/health`                 | Service health check                  |
| `GET /api/version`                | Service version                       |
| `GET /api/actuator/**`            | Spring actuator endpoints             |
| `GET /api/user/channels/platforms`| List available platforms (public)     |
| `GET /api/enums/**`               | Enum / reference data                 |

(*) `/api/auth/me` is technically permitted but returns 401 if no valid token is provided.

---

## Login — User Account

```bash
curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"owner@acme-shop.com","password":"secret123"}' | jq .
```

### Success Response (200 OK)

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJWNG5LcThtUjJ4THBZb1p3MUEzQiIsIm1lcmNoYW50SWQiOiJhMDAwMDAiLCJlbWFpbCI6Im93bmVyQGFjbWUtc2hvcC5jb20iLCJuYW1lIjoiQWxpY2UgQ2hlbiIsInJvbGUiOiJtYWluIiwiaWF0IjoxNzQzMTIwMDAwLCJleHAiOjE3NDM3MjQ4MDB9.SIGNATURE",
  "user": {
    "id": "V4nKq8mR2xLpYoZw1A3B",
    "email": "owner@acme-shop.com",
    "name": "Alice Chen",
    "merchantId": "a00000",
    "merchantName": "ACME Shop",
    "role": "main"
  }
}
```

### Error Responses

| Condition                | HTTP Status | Body                                    |
|--------------------------|-------------|-----------------------------------------|
| Missing email or password| 400         | `{"error":"email 和 password 為必填"}`  |
| Account not found        | 401         | `{"error":"帳號或密碼錯誤"}`            |
| Wrong password           | 401         | `{"error":"帳號或密碼錯誤"}`            |
| Account disabled         | 403         | `{"error":"帳號已停用"}`                |

---

## Login — Platform Admin

```bash
curl -s -X POST http://localhost:8082/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{"email":"admin@simpleec.com","password":"AdminSecret!"}' | jq .
```

### Success Response (200 OK)

```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9...",
  "user": {
    "id": "PqR9sT2uV5wX8yZ0aB1C",
    "email": "admin@simpleec.com",
    "name": "Platform Admin",
    "role": "platform_admin"
  }
}
```

Note: The admin token has an empty `merchantId` (`""`). All `/api/admin/**` operations work across all merchants.

---

## Making Authenticated Requests

Store the token and include it in every subsequent request:

```bash
TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# List orders
curl -s http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer $TOKEN" | jq .

# Get current user info
curl -s http://localhost:8082/api/auth/me \
  -H "Authorization: Bearer $TOKEN" | jq .
```

---

## Authentication Error Codes

| HTTP Code | Scenario                                              |
|-----------|-------------------------------------------------------|
| 400       | Required field (`email`, `password`) missing from request body |
| 401       | No `Authorization` header; invalid token; expired token; wrong credentials |
| 403       | Valid token but insufficient role (e.g. non-admin calling `/api/admin/**`); account disabled |

### Example 401 Response

```json
{
  "error": "帳號或密碼錯誤"
}
```

### Example 403 Response (wrong role)

Spring Security returns a 403 with an empty body when `hasAuthority("ROLE_PLATFORM_ADMIN")` fails.

---

## CORS

The API allows cross-origin requests from the following origins (configurable via `simpleec.cors.allowed-origins`):

- `http://localhost:8080`
- `http://localhost:5173`
- `http://localhost:3000`

Allowed methods: `GET`, `POST`, `PUT`, `PATCH`, `DELETE`, `OPTIONS`
Allowed headers: `Content-Type`, `Authorization`, `Accept`, `X-Requested-With`
Credentials: allowed (`allowCredentials: true`)
