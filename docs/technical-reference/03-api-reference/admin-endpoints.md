# Admin API Endpoints

Platform administrator endpoints for managing merchants, accounts, and platforms. All admin endpoints require a JWT issued by `/api/admin/auth/login` with the `platform_admin` role.

**Base URL:** `http://localhost:8082`

**Authentication header:**
```
Authorization: Bearer <admin-token>
```

Spring Security enforces `.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")` on every request in this namespace. A user-role token (merchant `main`/`sub`) will receive 403 Forbidden.

---

## Response Envelope

All admin endpoints return an `AdminApiResponse<T>` envelope:

```json
{
  "code": 200,
  "message": "success",
  "data": { ... }
}
```

On errors, `data` is `null` and `message` contains the error description. The `code` field mirrors the HTTP status.

For paginated responses, `data` is a `PageResponse<T>`:

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

---

## Table of Contents

- [Admin Auth](#admin-auth)
  - [POST /api/admin/auth/login](#post-apiadminauthlogin)
- [Account Management](#account-management)
  - [GET /api/admin/account](#get-apiadminaccount)
  - [GET /api/admin/account/{id}](#get-apiadminaccountid)
  - [POST /api/admin/account](#post-apiadminaccount)
  - [PUT /api/admin/account/{id}](#put-apiadminaccountid)
  - [DELETE /api/admin/account/{id}](#delete-apiadminaccountid)
  - [POST /api/admin/account/{id}/reset-password](#post-apiadminaccountidresetpassword)
- [Merchant Management](#merchant-management)
  - [GET /api/admin/merchant](#get-apiadminmerchant)
  - [GET /api/admin/merchant/{id}](#get-apiadminmerchantid)
  - [POST /api/admin/merchant](#post-apiadminmerchant)
  - [PUT /api/admin/merchant/{id}](#put-apiadminmerchantid)
  - [DELETE /api/admin/merchant/{id}](#delete-apiadminmerchantid)
- [Platform Management](#platform-management)
  - [GET /api/admin/platform](#get-apiadminplatform)
  - [GET /api/admin/platform/{id}](#get-apiadminplatformid)
  - [POST /api/admin/platform](#post-apiadminplatform)
  - [PUT /api/admin/platform/{id}](#put-apiadminplatformid)
  - [DELETE /api/admin/platform/{id}](#delete-apiadminplatformid)

---

## Admin Auth

### POST /api/admin/auth/login

Authenticate as a platform administrator. Returns a JWT with role `platform_admin`.

**Authentication:** None required.

**Request Body:**

| Field      | Type   | Required | Description                   |
|------------|--------|----------|-------------------------------|
| `email`    | String | Yes      | Platform admin account email  |
| `password` | String | Yes      | Platform admin password       |

```bash
curl -s -X POST http://localhost:8082/api/admin/auth/login \
  -H "Content-Type: application/json" \
  -d '{
    "email": "admin@simpleec.com",
    "password": "AdminSecret!"
  }'
```

**Response (200 OK):**
```json
{
  "token": "eyJhbGciOiJIUzI1NiJ9.eyJzdWIiOiJQcVI5c1QydVY1d1g4eVowYUIxQyIsIm1lcmNoYW50SWQiOiIiLCJlbWFpbCI6ImFkbWluQHNpbXBsZWVjLmNvbSIsIm5hbWUiOiJQbGF0Zm9ybSBBZG1pbiIsInJvbGUiOiJwbGF0Zm9ybV9hZG1pbiIsImlhdCI6MTc0MzEyMDAwMCwiZXhwIjoxNzQzNzI0ODAwfQ.SIG",
  "user": {
    "id": "PqR9sT2uV5wX8yZ0aB1C",
    "email": "admin@simpleec.com",
    "name": "Platform Admin",
    "role": "platform_admin"
  }
}
```

Note: The admin token has `merchantId: ""`. All subsequent admin API calls operate across all merchants.

**Error Responses:** `400` missing fields, `401` wrong credentials, `403` account disabled.

---

## Account Management

Accounts are merchant user accounts (not platform admin accounts). Each account belongs to one merchant via `merchantId`.

### GET /api/admin/account

Paginated list of all accounts. Can be filtered by `merchantId` and/or keyword search.

**Authentication:** Platform admin Bearer token required.

**Query Parameters:**

| Parameter    | Type    | Default      | Description                                           |
|--------------|---------|--------------|-------------------------------------------------------|
| `page`       | Integer | `1`          | Page number (1-based). Min: 1.                        |
| `pageSize`   | Integer | `20`         | Results per page. Min: 1. Max: 100.                   |
| `merchantId` | String  | —            | Filter accounts belonging to a specific merchant      |
| `search`     | String  | —            | Search by account name or email (applied within merchantId filter if both given) |
| `sortBy`     | String  | `createdAt`  | Sort field (accepted but currently ignored; default sort used) |
| `order`      | String  | `desc`       | Sort order: `asc` or `desc`                           |

```bash
ADMIN_TOKEN="eyJhbGciOiJIUzI1NiJ9..."

# List all accounts
curl -s "http://localhost:8082/api/admin/account?page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Filter by merchant
curl -s "http://localhost:8082/api/admin/account?merchantId=a00000&page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Search within a merchant
curl -s "http://localhost:8082/api/admin/account?merchantId=a00000&search=alice&page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 12,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1,
    "items": [
      {
        "id": "V4nKq8mR2xLpYoZw1A3B",
        "merchantId": "a00000",
        "accountEmail": "owner@acme-shop.com",
        "accountName": "Alice Chen",
        "accountTel": "0912345678",
        "isMainAccount": true,
        "accessLevel": null,
        "status": "enable",
        "accountPassword": null,
        "createdAt": "2026-01-10T09:00:00",
        "updatedAt": "2026-01-10T09:00:00"
      }
    ]
  }
}
```

Note: `accountPassword` is always returned as `null` — passwords are never exposed via the API.

---

### GET /api/admin/account/{id}

Fetch a single account by its ID.

**Authentication:** Platform admin Bearer token required.

```bash
curl -s "http://localhost:8082/api/admin/account/V4nKq8mR2xLpYoZw1A3B" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "V4nKq8mR2xLpYoZw1A3B",
    "merchantId": "a00000",
    "accountEmail": "owner@acme-shop.com",
    "accountName": "Alice Chen",
    "accountTel": "0912345678",
    "isMainAccount": true,
    "accessLevel": null,
    "status": "enable",
    "accountPassword": null,
    "createdAt": "2026-01-10T09:00:00",
    "updatedAt": "2026-01-10T09:00:00"
  }
}
```

**Error Responses:** `404` if account not found, `500` on server error.

---

### POST /api/admin/account

Create a new merchant account.

**Authentication:** Platform admin Bearer token required.

**Request Body:**

| Field             | Type    | Required | Validation                              |
|-------------------|---------|----------|-----------------------------------------|
| `merchantId`      | String  | Yes      | Must reference an existing merchant     |
| `accountEmail`    | String  | Yes      | Valid email format; must be unique      |
| `accountPassword` | String  | Yes      | Minimum 8 characters                   |
| `accountName`     | String  | No       | Display name                            |
| `accountTel`      | String  | No       | Phone number                            |
| `isMainAccount`   | Boolean | No       | `true` = main account (role: `main`)    |
| `accessLevel`     | String  | No       | Custom access level for sub accounts    |
| `status`          | String  | No       | `"enable"` (default) or `"disable"`     |

```bash
curl -s -X POST http://localhost:8082/api/admin/account \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantId": "a00000",
    "accountEmail": "staff@acme-shop.com",
    "accountPassword": "StaffPass99",
    "accountName": "Bob Wang",
    "accountTel": "0987654321",
    "isMainAccount": false,
    "status": "enable"
  }'
```

**Response (201 Created):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "Nm8oOp9qRs0tUv1wXy2Z",
    "merchantId": "a00000",
    "accountEmail": "staff@acme-shop.com",
    "accountName": "Bob Wang",
    "accountTel": "0987654321",
    "isMainAccount": false,
    "accessLevel": null,
    "status": "enable",
    "accountPassword": null,
    "createdAt": "2026-03-28T10:00:00",
    "updatedAt": "2026-03-28T10:00:00"
  }
}
```

**Error Responses:**
- `400` missing required fields, invalid email format, password too short (<8 chars), merchant not found
- `409` email already exists
- `500` server error

---

### PUT /api/admin/account/{id}

Update an existing account. Only supply fields you want to change; unchanged fields are left as-is.

**Authentication:** Platform admin Bearer token required.

**Updatable Fields:**

| Field           | Type    | Description                                |
|-----------------|---------|--------------------------------------------|
| `accountName`   | String  | Display name                               |
| `accountEmail`  | String  | New email (validated, checked for uniqueness) |
| `accountTel`    | String  | Phone number                               |
| `isMainAccount` | Boolean | Promote/demote main account flag           |
| `accessLevel`   | String  | Custom access level                        |
| `status`        | String  | `"enable"` or `"disable"`                 |

Note: Password changes must use the dedicated `POST /api/admin/account/{id}/reset-password` endpoint.

```bash
curl -s -X PUT "http://localhost:8082/api/admin/account/Nm8oOp9qRs0tUv1wXy2Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "accountName": "Bob Wang (Manager)",
    "status": "enable"
  }'
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "Nm8oOp9qRs0tUv1wXy2Z",
    "merchantId": "a00000",
    "accountEmail": "staff@acme-shop.com",
    "accountName": "Bob Wang (Manager)",
    "status": "enable",
    "accountPassword": null
  }
}
```

**Error Responses:** `400` invalid email, `404` not found, `409` email conflict, `500` server error.

---

### DELETE /api/admin/account/{id}

Permanently delete an account.

**Authentication:** Platform admin Bearer token required.

```bash
curl -s -X DELETE "http://localhost:8082/api/admin/account/Nm8oOp9qRs0tUv1wXy2Z" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success"
}
```

**Error Responses:** `404` not found, `500` server error.

---

### POST /api/admin/account/{id}/reset-password

Reset the password for a specific account.

**Authentication:** Platform admin Bearer token required.

**Request Body:**

| Field         | Type   | Required | Validation                 |
|---------------|--------|----------|----------------------------|
| `newPassword` | String | Yes      | Minimum 8 characters       |

```bash
curl -s -X POST "http://localhost:8082/api/admin/account/V4nKq8mR2xLpYoZw1A3B/reset-password" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{"newPassword":"NewSecurePass42"}'
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success"
}
```

**Error Responses:** `400` missing or too-short password, `404` account not found, `500` server error.

---

## Merchant Management

Merchants are top-level tenants. Each merchant has its own set of accounts, channels, orders, and statistics.

### GET /api/admin/merchant

Paginated list of merchants, with optional keyword search.

**Authentication:** Platform admin Bearer token required.

**Query Parameters:**

| Parameter  | Type    | Default | Description                                               |
|------------|---------|---------|-----------------------------------------------------------|
| `page`     | Integer | `1`     | Page number (1-based)                                     |
| `pageSize` | Integer | `20`    | Results per page. Max: 100.                               |
| `search`   | String  | —       | Search by merchant name or email                          |

```bash
curl -s "http://localhost:8082/api/admin/merchant?page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Search
curl -s "http://localhost:8082/api/admin/merchant?search=acme" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 3,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1,
    "items": [
      {
        "id": "a00000",
        "merchantName": "ACME Shop",
        "merchantEmail": "contact@acme-shop.com",
        "merchantPhoneNumber": "02-12345678",
        "taxIdNumber": "12345678",
        "addressCity": "Taipei",
        "addressRegion": "Zhongshan District",
        "addressCountry": "TW",
        "addressZip": "104",
        "addressLine1": "No. 1, Section 1, Zhongshan N Rd",
        "addressLine2": null,
        "vipLevel": 1,
        "userLocalTimeZone": "Asia/Taipei",
        "status": "enable",
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00"
      }
    ]
  }
}
```

---

### GET /api/admin/merchant/{id}

Fetch a single merchant by ID.

**Authentication:** Platform admin Bearer token required.

```bash
curl -s "http://localhost:8082/api/admin/merchant/a00000" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):** Merchant object wrapped in AdminApiResponse. Same fields as list items above.

**Error Responses:** `404` not found, `500` server error.

---

### POST /api/admin/merchant

Create a new merchant (tenant). The ID is auto-generated as a 6-character NanoID prefix.

**Authentication:** Platform admin Bearer token required.

**Request Body:**

| Field                 | Type   | Required | Validation                                  |
|-----------------------|--------|----------|---------------------------------------------|
| `merchantName`        | String | Yes      | Merchant display name                        |
| `merchantEmail`       | String | Yes      | Valid email format; must be unique globally  |
| `merchantPhoneNumber` | String | No       | Business phone number                        |
| `taxIdNumber`         | String | No       | Tax/business registration number            |
| `addressCity`         | String | No       | City                                         |
| `addressRegion`       | String | No       | Region/district                              |
| `addressCountry`      | String | No       | ISO 3166-1 alpha-2 country code             |
| `addressZip`          | String | No       | Postal code                                  |
| `addressLine1`        | String | No       | Primary address line                         |
| `addressLine2`        | String | No       | Secondary address line                       |
| `vipLevel`            | Integer| No       | VIP tier level                               |
| `userLocalTimeZone`   | String | No       | IANA timezone (e.g. `"Asia/Taipei"`)        |
| `status`              | String | No       | `"enable"` (default) or `"disable"`         |

```bash
curl -s -X POST http://localhost:8082/api/admin/merchant \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantName": "New Store Ltd",
    "merchantEmail": "hello@newstore.com",
    "merchantPhoneNumber": "03-9876543",
    "taxIdNumber": "87654321",
    "addressCountry": "TW",
    "userLocalTimeZone": "Asia/Taipei",
    "status": "enable"
  }'
```

**Response (201 Created):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "b1c2d3",
    "merchantName": "New Store Ltd",
    "merchantEmail": "hello@newstore.com",
    "status": "enable",
    "createdAt": "2026-03-28T10:00:00",
    "updatedAt": "2026-03-28T10:00:00"
  }
}
```

**Error Responses:** `400` missing name or email, invalid email format. `409` email already exists. `500` server error.

---

### PUT /api/admin/merchant/{id}

Update merchant information. Supply only the fields you want to change.

**Authentication:** Platform admin Bearer token required.

**Updatable Fields:** `merchantName`, `merchantEmail` (validated, checked for uniqueness), `merchantPhoneNumber`, `taxIdNumber`, `addressCity`, `addressRegion`, `addressCountry`, `addressZip`, `addressLine1`, `addressLine2`, `addressPhoneNumber`, `vipLevel`, `userLocalTimeZone`, `payerName`, `payerEmail`, `payerPhoneNumber`, `status`.

```bash
curl -s -X PUT "http://localhost:8082/api/admin/merchant/b1c2d3" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "merchantName": "New Store Ltd (Upgraded)",
    "vipLevel": 2,
    "status": "enable"
  }'
```

**Response (200 OK):** Updated merchant object in AdminApiResponse envelope.

**Error Responses:** `400` invalid email, `404` not found, `409` email conflict, `500` server error.

---

### DELETE /api/admin/merchant/{id}

Permanently delete a merchant.

**Authentication:** Platform admin Bearer token required.

```bash
curl -s -X DELETE "http://localhost:8082/api/admin/merchant/b1c2d3" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success"
}
```

**Error Responses:** `404` not found, `500` server error.

---

## Platform Management

Platforms represent the e-commerce channels the system integrates with (Shopee, Momo, Yahoo, PChome, Cyberbiz, Easystore, etc.). Each platform has Kafka topic mappings and optionally stores API credentials.

### GET /api/admin/platform

Paginated list of platforms. Supports keyword search and active-status filter.

**Authentication:** Platform admin Bearer token required.

**Query Parameters:**

| Parameter  | Type    | Default | Description                                    |
|------------|---------|---------|------------------------------------------------|
| `page`     | Integer | `1`     | Page number (1-based)                          |
| `pageSize` | Integer | `20`    | Results per page. Max: 100.                    |
| `search`   | String  | —       | Search by platform name or Kafka queue topic   |
| `actived`  | Boolean | —       | Filter by active status (`true` or `false`)    |

```bash
curl -s "http://localhost:8082/api/admin/platform?page=1&pageSize=20" \
  -H "Authorization: Bearer $ADMIN_TOKEN"

# Active platforms only
curl -s "http://localhost:8082/api/admin/platform?actived=true" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "total": 6,
    "page": 1,
    "pageSize": 20,
    "totalPages": 1,
    "items": [
      {
        "id": "PLT_SHOPEE_001",
        "platformName": "shopee",
        "queueTopic": "shopee",
        "currency": "TWD",
        "actived": true,
        "shipOptions": null,
        "credential1": null,
        "credential2": null,
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00"
      },
      {
        "id": "PLT_MOMO_001",
        "platformName": "momo",
        "queueTopic": "momo",
        "currency": "TWD",
        "actived": true,
        "shipOptions": null,
        "credential1": null,
        "credential2": null,
        "createdAt": "2026-01-01T00:00:00",
        "updatedAt": "2026-01-01T00:00:00"
      }
    ]
  }
}
```

Note: `credential1` and `credential2` store API keys/secrets for the platform. These fields are visible in admin API but should be treated as sensitive.

---

### GET /api/admin/platform/{id}

Fetch a single platform by ID.

**Authentication:** Platform admin Bearer token required.

```bash
curl -s "http://localhost:8082/api/admin/platform/PLT_SHOPEE_001" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):** Platform object in AdminApiResponse envelope.

**Error Responses:** `404` not found, `500` server error.

---

### POST /api/admin/platform

Create a new platform configuration.

**Authentication:** Platform admin Bearer token required.

**Request Body:**

| Field          | Type    | Required | Validation                                   |
|----------------|---------|----------|----------------------------------------------|
| `platformName` | String  | Yes      | Must be unique (case-sensitive)              |
| `queueTopic`   | String  | No       | Kafka topic prefix (defaults handled by code)|
| `currency`     | String  | No       | ISO 4217 currency code. Default: `"TWD"`    |
| `actived`      | Boolean | No       | Default: `true`                              |
| `credential1`  | String  | No       | Platform API key or app ID                   |
| `credential2`  | String  | No       | Platform API secret or token                 |
| `shipOptions`  | Object  | No       | JSON object for platform-specific ship options |

```bash
curl -s -X POST http://localhost:8082/api/admin/platform \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "platformName": "easystore",
    "queueTopic": "easystore",
    "currency": "TWD",
    "actived": true
  }'
```

**Response (201 Created):**
```json
{
  "code": 200,
  "message": "success",
  "data": {
    "id": "Fg7hIj8kLm9nOp0qRs1T",
    "platformName": "easystore",
    "queueTopic": "easystore",
    "currency": "TWD",
    "actived": true,
    "createdAt": "2026-03-28T10:00:00",
    "updatedAt": "2026-03-28T10:00:00"
  }
}
```

**Error Responses:** `400` missing platform name. `409` platform name already exists. `500` server error.

---

### PUT /api/admin/platform/{id}

Update platform settings.

**Authentication:** Platform admin Bearer token required.

**Updatable Fields:** `platformName` (validated unique), `queueTopic`, `currency`, `actived`, `credential1`, `credential2`, `shipOptions`.

```bash
curl -s -X PUT "http://localhost:8082/api/admin/platform/Fg7hIj8kLm9nOp0qRs1T" \
  -H "Authorization: Bearer $ADMIN_TOKEN" \
  -H "Content-Type: application/json" \
  -d '{
    "actived": false
  }'
```

**Response (200 OK):** Updated platform object in AdminApiResponse envelope.

**Error Responses:** `404` not found, `409` name conflict, `500` server error.

---

### DELETE /api/admin/platform/{id}

Permanently delete a platform configuration.

**Authentication:** Platform admin Bearer token required.

```bash
curl -s -X DELETE "http://localhost:8082/api/admin/platform/Fg7hIj8kLm9nOp0qRs1T" \
  -H "Authorization: Bearer $ADMIN_TOKEN"
```

**Response (200 OK):**
```json
{
  "code": 200,
  "message": "success"
}
```

**Error Responses:** `404` not found, `500` server error.
