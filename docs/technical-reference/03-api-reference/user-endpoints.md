# User API Endpoints

All user-facing endpoints require a valid Bearer JWT token unless noted as public.

**Base URL:** `http://localhost:8082`

**Authentication header:**
```
Authorization: Bearer <token>
```

All data is scoped to the merchant encoded in the JWT. A user from merchant A can never read or modify merchant B's data.

---

## Table of Contents

- [Auth](#auth)
  - [POST /api/auth/login](#post-apiauthlogin)
  - [GET /api/auth/me](#get-apiauthme)
  - [POST /api/auth/logout](#post-apiauthlogout)
- [Orders](#orders)
  - [GET /api/user/orders](#get-apiuserorders)
  - [POST /api/user/orders](#post-apiuserorders)
  - [GET /api/user/orders/{id}](#get-apiuserordersid)
  - [PATCH /api/user/orders/{id}](#patch-apiuserordersid)
  - [GET /api/user/orders/{id}/shipments](#get-apiuserordersidshipments)
  - [GET /api/user/orders/{id}/status-logs](#get-apiuserordersidstatus-logs)
- [Shipments](#shipments)
  - [GET /api/user/shipments](#get-apiusershipments)
  - [POST /api/user/shipments](#post-apiusershipments)
  - [GET /api/user/shipments/{id}](#get-apiusershipmentsid)
  - [PATCH /api/user/shipments/{id}](#patch-apiusershipmentsid)
  - [GET /api/user/shipments/by-order/{orderId}](#get-apiusershipmentsby-orderorderid)
- [Refunds](#refunds)
  - [GET /api/user/refunds](#get-apiuserrefunds)
  - [POST /api/user/refunds](#post-apiuserrefunds)
  - [GET /api/user/refunds/{id}](#get-apiuserrefundsid)
  - [PATCH /api/user/refunds/{id}](#patch-apiuserrefundsid)
- [Inventory](#inventory)
  - [GET /api/user/inventory](#get-apiuserinventory)
  - [GET /api/user/inventory/low-stock](#get-apiuserinventorylow-stock)
  - [PATCH /api/user/inventory/{productId}](#patch-apiuserinventoryproductid)
- [Reports](#reports)
  - [GET /api/user/reports/sales](#get-apiuserreportssales)
  - [GET /api/user/reports/profit](#get-apiuserreportsprofit)
- [Stats](#stats)
  - [GET /api/user/stats/daily](#get-apiuserstatsdaily)
  - [GET /api/user/stats/today](#get-apiuserstatstoday)
- [Enums](#enums)
  - [GET /api/enums/order-statuses](#get-apienumsorder-statuses)
- [Health](#health)
  - [GET /api/health](#get-apihealth)
  - [GET /api/version](#get-apiversion)

---

## Auth

### POST /api/auth/login

Authenticate with email and password. Returns a JWT valid for 7 days.

**Authentication:** None required.

**Request Body:**

| Field      | Type   | Required | Description      |
|------------|--------|----------|------------------|
| `email`    | String | Yes      | Account email    |
| `password` | String | Yes      | Account password |

```bash
curl -s -X POST http://localhost:8082/api/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "owner@acme-shop.com",
    "password": "secret123"
  }'
```

**Response (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJWNG5LcThtUjJ4THBZb1p3MUEzQiIsIm1lcmNoYW50SWQiOiJhMDAwMDAiLCJlbWFpbCI6Im93bmVyQGFjbWUtc2hvcC5jb20iLCJuYW1lIjoiQWxpY2UgQ2hlbiIsInJvbGUiOiJtYWluIiwiaWF0IjoxNzQzMTIwMDAwLCJleHAiOjE3NDM3MjQ4MDB9.SIG",
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

**Error Responses:** `400` missing fields, `401` wrong credentials, `403` account disabled.

---

### GET /api/auth/me

Returns the identity of the currently authenticated user decoded from the JWT.

**Authentication:** Bearer token required.

```bash
curl -s http://localhost:8082/api/auth/me \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "id": "V4nKq8mR2xLpYoZw1A3B",
  "email": "owner@acme-shop.com",
  "name": "Alice Chen",
  "merchantId": "a00000",
  "role": "main"
}
```

**Error Responses:** `401` if no valid token.

---

### POST /api/auth/logout

Stateless logout. Because JWTs are not tracked server-side, this endpoint always succeeds. The client is responsible for discarding the token.

**Authentication:** Bearer token (optional — returns 200 regardless).

```bash
curl -s -X POST http://localhost:8082/api/auth/logout \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):** Empty body.

---

## Orders

### GET /api/user/orders

Paginated list of orders for the authenticated merchant. Supports filtering by channel or status.

**Authentication:** Bearer token required.

**Query Parameters:**

| Parameter  | Type    | Default | Description                                                      |
|------------|---------|---------|------------------------------------------------------------------|
| `page`     | Integer | `1`     | Page number (1-based). Min: 1.                                   |
| `pageSize` | Integer | `10`    | Results per page. Min: 1. Max: 100.                              |
| `status`   | String  | —       | Filter by order status code (e.g. `pending`, `shipped`).         |
| `channelId`| String  | —       | Filter by channel ID. Mutually exclusive with `status` (status takes precedence). |

```bash
# All orders, page 1
curl -s "http://localhost:8082/api/user/orders" \
  -H "Authorization: Bearer $TOKEN"

# Filter by status
curl -s "http://localhost:8082/api/user/orders?status=pending&page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"

# Filter by channel
curl -s "http://localhost:8082/api/user/orders?channelId=CHANNEL_SHOPEE_001&page=1&pageSize=25" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK) — UserPageResponse:**
```json
{
  "data": [
    {
      "id": "Kp7mNqR3xVoWyZj2B5Lc",
      "merchantId": "a00000",
      "orderNumber": "2024031500001",
      "platform": "Shopee",
      "status": "pending",
      "totalAmount": 1250.00,
      "createdAt": "2026-03-15T08:30:00",
      "updatedAt": "2026-03-15T08:30:00",
      "syncStatus": null,
      "channelId": "CHANNEL_SHOPEE_001",
      "channelOrderId": "250315123456",
      "channelOrderNumber": "2024031500001",
      "orderStatus": "pending",
      "shippingFee": 60.00,
      "discountAmount": 0.00,
      "buyerName": "Wang Xiao-Ming",
      "buyerPhone": "0912345678",
      "buyerEmail": "buyer@example.com",
      "items": [
        {
          "channelItemId": "ITEM_001",
          "productName": "Premium Cotton T-Shirt",
          "quantity": 2,
          "unitPrice": 595.00
        }
      ],
      "buyerInfo": {},
      "shippingInfo": {
        "address": "123 Zhongshan Rd, Taipei"
      },
      "paymentMethod": "credit_card",
      "shippingMethod": "home_delivery",
      "paidAt": "2026-03-15T08:25:00",
      "shippedAt": null,
      "channelCreatedAt": "2026-03-15T08:20:00",
      "isRollback": false
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 142,
    "pages": 15
  }
}
```

---

### POST /api/user/orders

Submit an order via the API. The order is published to the `order.process` Kafka topic and processed asynchronously through the standard event pipeline (OrderUpsertConsumer → DB → statistics).

**Authentication:** Bearer token required.

**Request Body:**

| Field              | Type   | Required | Description                                    |
|--------------------|--------|----------|------------------------------------------------|
| `channelId`        | String | Yes      | Channel ID that must belong to your merchant   |
| `channelOrderId`   | String | Yes      | The platform's order identifier (unique per channel) |
| `channelOrderNumber`| String | No      | Human-readable order number from the platform  |
| `orderStatus`      | String | No       | Initial status code                            |
| `totalAmount`      | Number | No       | Order total amount                             |
| `...`              | Any    | No       | Additional order fields passed to orderData    |

```bash
curl -s -X POST http://localhost:8082/api/user/orders \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "channelId": "CHANNEL_SHOPEE_001",
    "channelOrderId": "250315999001",
    "channelOrderNumber": "SHP-2026031501",
    "orderStatus": "pending",
    "totalAmount": 890.00,
    "shippingFee": 60.00,
    "buyerName": "Lin Mei-Hua",
    "paymentMethod": "cod"
  }'
```

**Response (202 Accepted):**
```json
{
  "channelOrderId": "250315999001",
  "status": "accepted"
}
```

The `202 Accepted` status means the message has been published to Kafka. The order will appear in `GET /api/user/orders` once the consumer processes it (typically within 1-2 seconds).

**Error Responses:** `400` if `channelId` or `channelOrderId` missing, or channel not found. `403` if channel does not belong to your merchant.

---

### GET /api/user/orders/{id}

Fetch a single order by its internal OMS ID. PII fields (buyerName, buyerPhone, buyerEmail, shippingInfo) are decrypted before being returned.

**Authentication:** Bearer token required.

**Path Parameters:**

| Parameter | Type   | Description                     |
|-----------|--------|---------------------------------|
| `id`      | String | Internal OMS order ID (NanoID)  |

```bash
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "id": "Kp7mNqR3xVoWyZj2B5Lc",
  "merchantId": "a00000",
  "channelId": "CHANNEL_SHOPEE_001",
  "channelOrderId": "250315123456",
  "channelOrderNumber": "2024031500001",
  "orderStatus": "PENDING",
  "totalAmount": 1250.00,
  "shippingFee": 60.00,
  "discountAmount": 0.00,
  "buyerName": "Wang Xiao-Ming",
  "buyerPhone": "0912345678",
  "buyerEmail": "buyer@example.com",
  "shippingInfo": {
    "address": "123 Zhongshan Rd, Taipei"
  },
  "items": [...],
  "paymentMethod": "credit_card",
  "shippingMethod": "home_delivery",
  "paidAt": "2026-03-15T08:25:00",
  "shippedAt": null,
  "channelCreatedAt": "2026-03-15T08:20:00",
  "createdAt": "2026-03-15T08:30:00",
  "updatedAt": "2026-03-15T08:30:00",
  "isRollback": false
}
```

**Error Responses:** `404` if order does not exist or belongs to a different merchant.

---

### PATCH /api/user/orders/{id}

Perform an action on an order. Supported actions: `ship` (marks shipped and publishes SHIP_ORDER to the platform's fast topic) and `cancel` (marks cancelled and publishes CANCEL_ORDER_INTERNAL to order.process).

**Authentication:** Bearer token required.

**Path Parameters:**

| Parameter | Type   | Description                     |
|-----------|--------|---------------------------------|
| `id`      | String | Internal OMS order ID (NanoID)  |

**Request Body — Ship:**

| Field           | Type   | Required | Description                    |
|-----------------|--------|----------|--------------------------------|
| `action`        | String | Yes      | Must be `"ship"`               |
| `trackingNumber`| String | No       | Carrier tracking number        |
| `carrier`       | String | No       | Carrier/logistics company name |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "action": "ship",
    "trackingNumber": "799110012345678",
    "carrier": "BlackCat"
  }'
```

**Request Body — Cancel:**

| Field    | Type   | Required | Description         |
|----------|--------|----------|---------------------|
| `action` | String | Yes      | Must be `"cancel"`  |
| `reason` | String | No       | Cancellation reason |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"cancel","reason":"Customer requested cancellation"}'
```

**Response (200 OK):** Updated Order entity (same shape as GET /api/user/orders/{id}).

**Error Responses:** `400` if action is not `ship` or `cancel`. `404` if order not found or belongs to a different merchant.

---

### GET /api/user/orders/{id}/shipments

List all shipment records attached to a specific order.

**Authentication:** Bearer token required.

```bash
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc/shipments" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
[
  {
    "id": "Sh9pQr4tUvXwYzA2C6Df",
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "trackingNumber": "799110012345678",
    "logisticsCompany": "BlackCat",
    "shippingStatus": "pending",
    "shippedAt": "2026-03-16T10:00:00",
    "createdAt": "2026-03-16T10:00:00",
    "updatedAt": "2026-03-16T10:00:00"
  }
]
```

**Error Responses:** `404` if order does not exist or belongs to a different merchant.

---

### GET /api/user/orders/{id}/status-logs

Retrieve the full status change history for an order, sorted oldest-first. Use this to render a timeline in the UI.

**Authentication:** Bearer token required.

```bash
curl -s "http://localhost:8082/api/user/orders/Kp7mNqR3xVoWyZj2B5Lc/status-logs" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
[
  {
    "id": "Lg2nMo5pQrStUv7wXyZa",
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "fromStatus": null,
    "toStatus": "pending",
    "changedAt": "2026-03-15T08:30:00",
    "source": "channel_job",
    "createdAt": "2026-03-15T08:30:01"
  },
  {
    "id": "Bc3dEf6gHiJkLm8nOpQr",
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "fromStatus": "pending",
    "toStatus": "shipped",
    "changedAt": "2026-03-16T10:00:00",
    "source": "api",
    "createdAt": "2026-03-16T10:00:01"
  }
]
```

**Error Responses:** `404` if order does not exist or belongs to a different merchant.

---

## Shipments

### GET /api/user/shipments

Paginated list of all shipment records for the authenticated merchant, sorted by creation date descending.

**Authentication:** Bearer token required.

**Query Parameters:**

| Parameter  | Type    | Default | Description          |
|------------|---------|---------|----------------------|
| `page`     | Integer | `1`     | Page number (1-based)|
| `pageSize` | Integer | `10`    | Results per page     |

```bash
curl -s "http://localhost:8082/api/user/shipments?page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK) — UserPageResponse:**
```json
{
  "data": [
    {
      "id": "Sh9pQr4tUvXwYzA2C6Df",
      "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
      "trackingNumber": "799110012345678",
      "logisticsCompany": "BlackCat",
      "shippingStatus": "pending",
      "shippedAt": "2026-03-16T10:00:00",
      "createdAt": "2026-03-16T10:00:00",
      "updatedAt": "2026-03-16T10:00:00"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "total": 38,
    "pages": 2
  }
}
```

---

### POST /api/user/shipments

Create a new shipment record for an existing order. The order must belong to your merchant.

**Authentication:** Bearer token required.

**Request Body:**

| Field           | Type   | Required | Description                      |
|-----------------|--------|----------|----------------------------------|
| `orderId`       | String | Yes      | Internal OMS order ID            |
| `trackingNumber`| String | No       | Carrier tracking number          |
| `carrier`       | String | No       | Carrier/logistics company (maps to `logisticsCompany`) |

```bash
curl -s -X POST http://localhost:8082/api/user/shipments \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "trackingNumber": "799110012345678",
    "carrier": "BlackCat"
  }'
```

**Response (201 Created):**
```json
{
  "id": "Sh9pQr4tUvXwYzA2C6Df",
  "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
  "trackingNumber": "799110012345678",
  "logisticsCompany": "BlackCat",
  "shippingStatus": "pending",
  "shippedAt": "2026-03-16T10:00:00",
  "createdAt": "2026-03-16T10:00:00",
  "updatedAt": "2026-03-16T10:00:00"
}
```

**Error Responses:** `400` if `orderId` is missing. `404` if order not found or belongs to a different merchant.

---

### GET /api/user/shipments/{id}

Fetch a single shipment by its ID.

**Authentication:** Bearer token required.

```bash
curl -s "http://localhost:8082/api/user/shipments/Sh9pQr4tUvXwYzA2C6Df" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):** Single shipment object (same shape as POST response).

**Error Responses:** `404` if not found or parent order belongs to a different merchant.

---

### PATCH /api/user/shipments/{id}

Update the tracking number of an existing shipment.

**Authentication:** Bearer token required.

**Request Body:**

| Field           | Type   | Required | Description                   |
|-----------------|--------|----------|-------------------------------|
| `trackingNumber`| String | Yes      | New tracking number to set    |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/shipments/Sh9pQr4tUvXwYzA2C6Df" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"trackingNumber":"799110099999999"}'
```

**Response (200 OK):** Updated shipment object.

**Error Responses:** `404` if not found or belongs to a different merchant.

---

### GET /api/user/shipments/by-order/{orderId}

List all shipments for a specific order. An alternative to `GET /api/user/orders/{id}/shipments`.

**Authentication:** Bearer token required.

```bash
curl -s "http://localhost:8082/api/user/shipments/by-order/Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):** Array of shipment objects.

---

## Refunds

### GET /api/user/refunds

Paginated list of return/refund records. Optionally filter by `orderId`.

**Authentication:** Bearer token required.

**Query Parameters:**

| Parameter  | Type    | Default | Description                        |
|------------|---------|---------|------------------------------------|
| `page`     | Integer | `1`     | Page number (1-based)              |
| `pageSize` | Integer | `10`    | Results per page                   |
| `orderId`  | String  | —       | Filter refunds for a specific order (returns all as a non-paginated list) |

```bash
curl -s "http://localhost:8082/api/user/refunds?page=1&pageSize=10" \
  -H "Authorization: Bearer $TOKEN"

# Filter by order
curl -s "http://localhost:8082/api/user/refunds?orderId=Kp7mNqR3xVoWyZj2B5Lc" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK) — UserPageResponse:**
```json
{
  "data": [
    {
      "id": "a000002026031610Ab",
      "merchantId": "a00000",
      "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
      "channelReturnId": null,
      "returnStatus": "PENDING",
      "reason": "Product defective",
      "refundAmount": 1250.00,
      "requestedAt": "2026-03-16T14:00:00",
      "approvedAt": null,
      "rejectedAt": null,
      "createdAt": "2026-03-16T14:00:00",
      "updatedAt": "2026-03-16T14:00:00"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 10,
    "total": 5,
    "pages": 1
  }
}
```

---

### POST /api/user/refunds

Create a new refund/return request.

**Authentication:** Bearer token required.

**Request Body:**

| Field    | Type   | Required | Description                      |
|----------|--------|----------|----------------------------------|
| `orderId`| String | No       | Associated OMS order ID          |
| `reason` | String | No       | Reason for return                |
| `amount` | Number | No       | Refund amount                    |

```bash
curl -s -X POST http://localhost:8082/api/user/refunds \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "orderId": "Kp7mNqR3xVoWyZj2B5Lc",
    "reason": "Product defective",
    "amount": 1250.00
  }'
```

**Response (201 Created):** ReturnOrder object. The ID is generated as `{merchantPrefix}{yyyyMMddHHmmss}{random2}`.

---

### GET /api/user/refunds/{id}

Fetch a single refund record by ID.

**Authentication:** Bearer token required.

```bash
curl -s "http://localhost:8082/api/user/refunds/a000002026031610Ab" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):** Single ReturnOrder object.

**Error Responses:** `404` if not found or belongs to a different merchant.

---

### PATCH /api/user/refunds/{id}

Approve or reject a refund request.

**Authentication:** Bearer token required.

**Request Body:**

| Field    | Type   | Required | Description                          |
|----------|--------|----------|--------------------------------------|
| `action` | String | Yes      | `"approve"` or `"reject"`            |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/refunds/a000002026031610Ab" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"action":"approve"}'
```

**Response (200 OK):** Updated ReturnOrder object with `returnStatus` set to `APPROVED` or `REJECTED`.

**Error Responses:** `400` if action is not `approve` or `reject`. `404` if not found or belongs to a different merchant.

---

## Inventory

### GET /api/user/inventory

Paginated list of all products with their current quantity and safety quantity.

**Authentication:** Bearer token required.

**Query Parameters:**

| Parameter  | Type    | Default | Description          |
|------------|---------|---------|----------------------|
| `page`     | Integer | `1`     | Page number (1-based)|
| `pageSize` | Integer | `20`    | Results per page     |

```bash
curl -s "http://localhost:8082/api/user/inventory?page=1&pageSize=20" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK) — UserPageResponse:**
```json
{
  "data": [
    {
      "id": "Pr5qRs6tUvWxYzA1B2Cd",
      "merchantId": "a00000",
      "sku": "TSHIRT-BLUE-L",
      "productName": "Premium Cotton T-Shirt (Blue/L)",
      "quantity": 45,
      "safetyQuantity": 10,
      "costPrice": 300.00,
      "createdAt": "2026-01-10T09:00:00",
      "updatedAt": "2026-03-15T08:30:00"
    }
  ],
  "pagination": {
    "page": 1,
    "pageSize": 20,
    "total": 87,
    "pages": 5
  }
}
```

---

### GET /api/user/inventory/low-stock

Returns only products whose `quantity <= safetyQuantity`.

**Authentication:** Bearer token required.

**Query Parameters:** Same as GET /api/user/inventory (`page`, `pageSize`).

```bash
curl -s "http://localhost:8082/api/user/inventory/low-stock" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):** Same shape as GET /api/user/inventory, filtered to low-stock products only.

---

### PATCH /api/user/inventory/{productId}

Manually set (overwrite) the absolute quantity of a product. This is a stock-take correction, not a delta adjustment.

**Authentication:** Bearer token required.

**Path Parameters:**

| Parameter   | Type   | Description                       |
|-------------|--------|-----------------------------------|
| `productId` | String | Internal OMS product ID (NanoID)  |

**Request Body:**

| Field      | Type    | Required | Description                                |
|------------|---------|----------|--------------------------------------------|
| `quantity` | Integer | Yes      | New absolute inventory quantity (>= 0)     |

```bash
curl -s -X PATCH "http://localhost:8082/api/user/inventory/Pr5qRs6tUvWxYzA1B2Cd" \
  -H "Authorization: Bearer $TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"quantity":50}'
```

**Response (200 OK):** Updated Product object.

**Error Responses:** `400` if quantity is null or negative. `404` if product not found or belongs to a different merchant.

---

## Reports

### GET /api/user/reports/sales

Returns a sales summary, daily breakdown, and platform breakdown for a date range.

**Authentication:** Bearer token required.

**Query Parameters:**

| Parameter   | Type   | Default      | Description                                   |
|-------------|--------|--------------|-----------------------------------------------|
| `from`      | Date   | 7 days ago   | Start date (ISO-8601: `YYYY-MM-DD`)           |
| `to`        | Date   | Today        | End date (ISO-8601: `YYYY-MM-DD`, inclusive)  |
| `channelId` | String | —            | Optional: limit to a specific channel         |

```bash
curl -s "http://localhost:8082/api/user/reports/sales?from=2026-03-20&to=2026-03-26" \
  -H "Authorization: Bearer $TOKEN"

# With channel filter
curl -s "http://localhost:8082/api/user/reports/sales?from=2026-03-01&to=2026-03-26&channelId=CHANNEL_SHOPEE_001" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "summary": {
    "totalOrders": 312,
    "totalAmount": 428650.00,
    "totalShipped": 280,
    "totalCompleted": 240,
    "totalCancelled": 18,
    "returnRate": 2.56
  },
  "byDate": [
    {
      "date": "2026-03-20",
      "orderCount": 38,
      "amount": 52400.00
    },
    {
      "date": "2026-03-21",
      "orderCount": 45,
      "amount": 61800.00
    },
    {
      "date": "2026-03-22",
      "orderCount": 0,
      "amount": 0.00
    },
    {
      "date": "2026-03-23",
      "orderCount": 0,
      "amount": 0.00
    },
    {
      "date": "2026-03-24",
      "orderCount": 52,
      "amount": 71250.00
    },
    {
      "date": "2026-03-25",
      "orderCount": 88,
      "amount": 120750.00
    },
    {
      "date": "2026-03-26",
      "orderCount": 89,
      "amount": 122450.00
    }
  ],
  "byPlatform": [
    {
      "platformId": "shopee",
      "orderCount": 180,
      "amount": 247500.00,
      "percentage": 57.69
    },
    {
      "platformId": "momo",
      "orderCount": 95,
      "amount": 130500.00,
      "percentage": 30.45
    },
    {
      "platformId": "cyberbiz",
      "orderCount": 37,
      "amount": 50650.00,
      "percentage": 11.86
    }
  ]
}
```

`returnRate` is expressed as a percentage (e.g. `2.56` means 2.56%). `byDate` is always returned in ascending date order to facilitate chart rendering. `byPlatform` is sorted by `orderCount` descending.

---

### GET /api/user/reports/profit

Returns gross profit estimate. Because daily statistics are aggregated at channel/date level without product-level revenue breakdown, per-product `revenue` and `profit` are returned as `null`. This endpoint provides a cost reference using `costPrice` from the product catalogue.

**Authentication:** Bearer token required.

**Query Parameters:** Same as sales report (`from`, `to`).

```bash
curl -s "http://localhost:8082/api/user/reports/profit?from=2026-03-01&to=2026-03-26" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "totalRevenue": 428650.00,
  "totalCost": null,
  "grossProfit": null,
  "grossMargin": null,
  "byProduct": [
    {
      "productId": "Pr5qRs6tUvWxYzA1B2Cd",
      "productName": "Premium Cotton T-Shirt (Blue/L)",
      "revenue": null,
      "cost": 300.00,
      "profit": null
    }
  ]
}
```

---

## Stats

### GET /api/user/stats/daily

Returns raw `DailyStatistics` records for a date range. Each row corresponds to one channel on one day.

**Authentication:** Bearer token required.

**Query Parameters:**

| Parameter   | Type   | Default      | Description                                   |
|-------------|--------|--------------|-----------------------------------------------|
| `from`      | Date   | 7 days ago   | Start date (ISO-8601: `YYYY-MM-DD`)           |
| `to`        | Date   | Today        | End date (ISO-8601: `YYYY-MM-DD`, inclusive)  |
| `channelId` | String | —            | Optional channel filter                        |

```bash
curl -s "http://localhost:8082/api/user/stats/daily?from=2026-03-20&to=2026-03-26" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": [
    {
      "id": "stats-row-id",
      "merchantId": "a00000",
      "channelId": "CHANNEL_SHOPEE_001",
      "platformId": "shopee",
      "statDate": "2026-03-26",
      "newOrderCount": 42,
      "newOrderAmount": 57750.00,
      "shippedCount": 38,
      "completedCount": 32,
      "cancelledCount": 3,
      "refundCount": 1,
      "dirtyAt": null,
      "createdAt": "2026-03-26T23:59:59",
      "updatedAt": "2026-03-26T23:59:59"
    }
  ]
}
```

---

### GET /api/user/stats/today

Returns today's aggregated statistics across all channels.

**Authentication:** Bearer token required.

```bash
curl -s "http://localhost:8082/api/user/stats/today" \
  -H "Authorization: Bearer $TOKEN"
```

**Response (200 OK):**
```json
{
  "success": true,
  "data": {
    "date": "2026-03-28",
    "totalOrders": 89,
    "totalAmount": 122450.00,
    "channels": [
      {
        "channelId": "CHANNEL_SHOPEE_001",
        "platformId": "shopee",
        "newOrderCount": 55,
        "newOrderAmount": 75625.00
      },
      {
        "channelId": "CHANNEL_MOMO_001",
        "platformId": "momo",
        "newOrderCount": 34,
        "newOrderAmount": 46825.00
      }
    ]
  }
}
```

---

## Enums

### GET /api/enums/order-statuses

Returns all valid order status codes with labels and descriptions. This endpoint is public — no authentication required. Use this to populate status filter dropdowns in the UI.

**Authentication:** None required.

```bash
curl -s http://localhost:8082/api/enums/order-statuses
```

**Response (200 OK):**
```json
{
  "data": [
    {
      "code": "pending",
      "label": "待付款",
      "description": "Order placed but payment not yet confirmed"
    },
    {
      "code": "confirmed",
      "label": "已確認",
      "description": "Payment confirmed, awaiting fulfillment"
    },
    {
      "code": "ready_to_ship",
      "label": "備貨中",
      "description": "Items picked and packed, ready for carrier pickup"
    },
    {
      "code": "shipping",
      "label": "配送中",
      "description": "Handed to carrier, in transit"
    },
    {
      "code": "shipped",
      "label": "已出貨",
      "description": "Shipment confirmed on platform"
    },
    {
      "code": "completed",
      "label": "已完成",
      "description": "Buyer confirmed receipt"
    },
    {
      "code": "cancelled",
      "label": "已取消",
      "description": "Order cancelled"
    }
  ]
}
```

---

## Health

### GET /api/health

Basic liveness check. No authentication required.

```bash
curl -s http://localhost:8082/api/health
```

**Response (200 OK):**
```json
{
  "status": "UP",
  "service": "SimpleEC OMS API",
  "timestamp": 1743120000000
}
```

---

### GET /api/version

Returns the deployed service version. No authentication required.

```bash
curl -s http://localhost:8082/api/version
```

**Response (200 OK):**
```json
{
  "version": "1.0.0",
  "build": 1743120000000
}
```
