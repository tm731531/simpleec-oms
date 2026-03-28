# Error Codes & Response Format

This document describes the HTTP status codes used by SimpleEC OMS, the two response envelope formats, and common error scenarios with example responses.

---

## Response Envelopes

There are two response envelope shapes depending on which API family you are calling.

### User API Responses

User-facing endpoints (`/api/auth/**`, `/api/user/**`, `/api/enums/**`, `/api/health`) return direct JSON objects or, for lists, a `UserPageResponse`:

**Single object (direct):**
```json
{
  "id": "Kp7mNqR3xVoWyZj2B5Lc",
  "merchantId": "a00000",
  "orderStatus": "pending",
  ...
}
```

**Paginated list (UserPageResponse):**
```json
{
  "data": [ ... ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 142,
    "pages": 15
  }
}
```

**Error (direct):**
```json
{
  "error": "帳號或密碼錯誤"
}
```

### Admin API Responses

Admin endpoints (`/api/admin/**`) always use the `AdminApiResponse<T>` envelope:

**Success with data:**
```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

**Success with paginated data:**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 45,
    "page": 1,
    "pageSize": 20,
    "totalPages": 3,
    "items": [ ... ]
  }
}
```

**Success without data (e.g. DELETE):**
```json
{
  "code": 200,
  "message": "success"
}
```

**Error:**
```json
{
  "code": 404,
  "message": "Account not found: V4nKq8mR2xLpYoZw1A3B"
}
```

---

## HTTP Status Codes

| Status | Name                  | When Used                                                                          |
|--------|-----------------------|------------------------------------------------------------------------------------|
| `200`  | OK                    | Successful GET, PATCH, PUT, DELETE. Also used by admin POST on success (alongside the `code: 200` in the envelope). |
| `201`  | Created               | Successful POST that created a new resource (e.g. POST /api/user/shipments).       |
| `202`  | Accepted              | Message accepted for async processing (POST /api/user/orders publishes to Kafka).  |
| `400`  | Bad Request           | Missing required field, failed validation (email format, password length, etc.).   |
| `401`  | Unauthorized          | No Authorization header; invalid or expired JWT; wrong credentials on login.       |
| `403`  | Forbidden             | Valid token, but insufficient role (e.g. merchant account calling admin endpoint); account disabled. |
| `404`  | Not Found             | Resource does not exist, or exists but belongs to a different merchant.            |
| `409`  | Conflict              | Duplicate unique value (email already registered, platform name already exists).   |
| `500`  | Internal Server Error | Unexpected server-side error.                                                      |

---

## 400 Bad Request

Returned when the request is syntactically or semantically invalid before reaching the database.

### Missing required fields (auth)
```
HTTP/1.1 400 Bad Request

{
  "error": "email 和 password 為必填"
}
```

### Missing required field (order submission)
```
HTTP/1.1 400 Bad Request

{
  "error": "channelId and channelOrderId are required"
}
```

### Channel not found for merchant
```
HTTP/1.1 400 Bad Request

{
  "error": "Channel not found: CHANNEL_UNKNOWN_001"
}
```

### Invalid email format (admin account creation)
```
HTTP/1.1 400 Bad Request

{
  "code": 400,
  "message": "Invalid email format: not-an-email"
}
```

### Password too short (admin account creation)
```
HTTP/1.1 400 Bad Request

{
  "code": 400,
  "message": "Password must be at least 8 characters"
}
```

### Merchant not found when creating account
```
HTTP/1.1 400 Bad Request

{
  "code": 400,
  "message": "Merchant not found: xxxxxx"
}
```

### Invalid action on order PATCH
```
HTTP/1.1 400 Bad Request
(empty body)
```

### Invalid quantity on inventory PATCH
```
HTTP/1.1 400 Bad Request
(empty body)
```

---

## 401 Unauthorized

Returned when authentication is missing or invalid.

### No token provided (Spring Security default)
```
HTTP/1.1 401 Unauthorized
(empty body or Spring default error page)
```

### Wrong credentials on login
```
HTTP/1.1 401 Unauthorized

{
  "error": "帳號或密碼錯誤"
}
```

### Expired or invalid token (JwtAuthFilter)
```
HTTP/1.1 401 Unauthorized
(empty body — JwtAuthFilter sets status 401 and returns)
```

### Not authenticated on /api/auth/me
```
HTTP/1.1 401 Unauthorized

{
  "error": "未授權"
}
```

---

## 403 Forbidden

Returned when the user is authenticated but lacks the required role or the account is disabled.

### Account disabled on login
```
HTTP/1.1 403 Forbidden

{
  "error": "帳號已停用"
}
```

### Merchant account calling admin endpoint
```
HTTP/1.1 403 Forbidden
(empty body — Spring Security hasAuthority check)
```

### Accessing order that belongs to a different merchant (returns 404 to prevent data existence leakage)
```
HTTP/1.1 404 Not Found
(empty body)
```

### Channel does not belong to your merchant (order submission)
```
HTTP/1.1 403 Forbidden

{
  "error": "Channel does not belong to your merchant"
}
```

---

## 404 Not Found

Returned when a resource cannot be found, or when a resource exists but belongs to a different merchant (the system returns 404 instead of 403 to avoid leaking the existence of other merchants' data).

### Order not found (user API)
```
HTTP/1.1 404 Not Found
(empty body)
```

### Account not found (admin API)
```
HTTP/1.1 404 Not Found

{
  "code": 404,
  "message": "Account not found: V4nKq8mR2xLpYoZw1A3B"
}
```

### Merchant not found (admin API)
```
HTTP/1.1 404 Not Found

{
  "code": 404,
  "message": "Merchant not found: xxxxxx"
}
```

### Platform not found (admin API)
```
HTTP/1.1 404 Not Found

{
  "code": 404,
  "message": "Platform not found: PLT_UNKNOWN"
}
```

---

## 409 Conflict

Returned when a uniqueness constraint would be violated.

### Duplicate email (account creation)
```
HTTP/1.1 409 Conflict

{
  "code": 409,
  "message": "Account email already exists: staff@acme-shop.com"
}
```

### Duplicate merchant email
```
HTTP/1.1 409 Conflict

{
  "code": 409,
  "message": "Merchant email already exists: contact@acme-shop.com"
}
```

### Duplicate platform name
```
HTTP/1.1 409 Conflict

{
  "code": 409,
  "message": "Platform name already exists: shopee"
}
```

---

## 500 Internal Server Error

Returned by admin endpoints when an unexpected exception occurs. User endpoints do not typically wrap errors in this way.

```
HTTP/1.1 500 Internal Server Error

{
  "code": 500,
  "message": "Failed to list accounts: Connection refused"
}
```

---

## Pagination Reference

User API paginated responses use `UserPageResponse`:

```json
{
  "data": [
    { "id": "...", ... },
    { "id": "...", ... }
  ],
  "pagination": {
    "page": 2,
    "pageSize": 10,
    "total": 142,
    "pages": 15
  }
}
```

| Field      | Type    | Description                            |
|------------|---------|----------------------------------------|
| `data`     | Array   | Items on the current page              |
| `page`     | Integer | Current page number (1-based)          |
| `pageSize` | Integer | Number of items requested per page     |
| `total`    | Long    | Total number of matching records in DB |
| `pages`    | Integer | Total number of pages (`ceil(total/pageSize)`) |

Admin API paginated responses use `PageResponse`:

```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 45,
    "page": 1,
    "pageSize": 20,
    "totalPages": 3,
    "items": [ ... ]
  }
}
```

| Field        | Type    | Description                            |
|--------------|---------|----------------------------------------|
| `items`      | Array   | Items on the current page              |
| `page`       | Integer | Current page number (1-based)          |
| `pageSize`   | Integer | Number of items requested per page     |
| `total`      | Long    | Total number of matching records       |
| `totalPages` | Integer | Total number of pages                  |

**Pagination defaults and limits:**

| Endpoint group       | Default page | Default pageSize | Max pageSize |
|----------------------|-------------|-----------------|--------------|
| `GET /api/user/orders` | 1         | 10              | 100          |
| `GET /api/user/shipments` | 1      | 10              | (none enforced) |
| `GET /api/user/refunds` | 1        | 10              | (none enforced) |
| `GET /api/user/inventory` | 1      | 20              | (none enforced) |
| `GET /api/admin/account` | 1       | 20              | 100          |
| `GET /api/admin/merchant` | 1      | 20              | 100          |
| `GET /api/admin/platform` | 1      | 20              | 100          |

---

## Common Error Scenarios

### Scenario 1: Calling a protected endpoint without a token

```bash
curl -s http://localhost:8082/api/user/orders
# → 401 Unauthorized (empty body)
```

### Scenario 2: Token expired

Tokens are valid for 7 days. After expiry, JwtAuthFilter rejects the token and returns 401.

```bash
curl -s http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer <expired-token>"
# → 401 Unauthorized (empty body)
```

Solution: Call `POST /api/auth/login` again to obtain a new token.

### Scenario 3: Merchant user trying to access admin endpoint

```bash
curl -s http://localhost:8082/api/admin/account \
  -H "Authorization: Bearer <merchant-token>"
# → 403 Forbidden (empty body)
```

### Scenario 4: Accessing another merchant's order

```bash
# Order Kp7m... belongs to merchant a00000
# Authenticated as merchant a00001
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer <merchant-a00001-token>"
# → 404 Not Found (empty body)
# 404 is intentional — prevents confirming the resource exists
```

### Scenario 5: Submitting an order with an invalid status

The OMS is a passive sync system and does not enforce status transitions. Any status code accepted by `OrderStatusEnum.fromCode()` is valid. Unknown status codes are silently handled (the order is not created, and an empty page is returned by the list endpoint for that status filter).

### Scenario 6: Creating an account with an existing email

```bash
curl -s -X POST http://localhost:8082/api/admin/account \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"merchantId":"a00000","accountEmail":"owner@acme-shop.com","accountPassword":"pass1234"}'
# → 409 Conflict
# {"code":409,"message":"Account email already exists: owner@acme-shop.com"}
```

### Scenario 7: Order submitted asynchronously (202)

`POST /api/user/orders` returns `202 Accepted` immediately. The order is not yet in the database at this point — it has been queued in Kafka. Poll `GET /api/user/orders` after a moment to confirm persistence.

```bash
curl -s -X POST http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"channelId":"CHANNEL_SHOPEE_001","channelOrderId":"250328001","totalAmount":500}'
# → 202 Accepted
# {"channelOrderId":"250328001","status":"accepted"}
```
