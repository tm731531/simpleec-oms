# JWT Authentication

SimpleEC OMS uses stateless JWT (JSON Web Token) authentication via Spring Security. Every protected API request must carry a valid Bearer token obtained at login.

---

## 1. Login Flow

```
Client                              Server
  |                                   |
  |-- POST /api/auth/login ---------->|
  |   { email, password }            |
  |                                   |-- load Account from DB
  |                                   |-- BCrypt.verify(password, storedHash)
  |                                   |-- JwtUtil.generateToken(accountId, merchantId, email, name, role)
  |                                   |
  |<-- 200 OK ------------------------|
  |   { token: "eyJ...", expiresIn: 604800 }
  |                                   |
  |-- GET /api/user/orders ---------->|
  |   Authorization: Bearer eyJ...   |
  |                                   |-- JwtAuthFilter.doFilterInternal()
  |                                   |-- JwtUtil.isValid(token)
  |                                   |-- JwtUtil.parseToken(token) -> Claims
  |                                   |-- new UserPrincipal(accountId, merchantId, email, name, role)
  |                                   |-- SecurityContextHolder.setAuthentication(auth)
  |                                   |-- controller method runs with @AuthenticationPrincipal available
  |<-- 200 OK ------------------------|
```

Password hashing uses BCrypt via Spring Security's `BCryptPasswordEncoder`.
Token expiry defaults to 604800 seconds (7 days) and is controlled by `jwt.expiration` in `application.yml`.

---

## 2. Token Structure

A JWT has three Base64URL-encoded parts separated by dots: `header.payload.signature`

### Header
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

### Payload (Claims)
```json
{
  "sub": "acc_NANO_ID",        // accountId — used as subject
  "merchantId": "mrc_NANO_ID", // scopes all DB queries to this merchant
  "email": "user@example.com",
  "name": "王小明",
  "role": "main",              // "platform_admin" | "main" | "sub"
  "iat": 1743000000,           // issued-at (Unix epoch)
  "exp": 1743604800            // expiry (iat + 604800)
}
```

### Signature
`HMAC-SHA256(base64url(header) + "." + base64url(payload), JWT_SECRET)`

The `JWT_SECRET` is loaded from the environment variable `jwt.secret` (set in `.env` and injected via `application.yml`). It must be at least 32 bytes (256 bits) to meet the HS256 minimum.

---

## 3. JwtAuthFilter Behavior

`JwtAuthFilter` extends `OncePerRequestFilter` and runs before `UsernamePasswordAuthenticationFilter`.

**For public paths** (see Section 4): `shouldNotFilter()` returns `true` — the filter is skipped entirely.

**For protected paths:**
1. Read `Authorization` header; extract the `Bearer ` prefix.
2. Call `JwtUtil.isValid(token)`.
   - Valid: parse claims, construct `UserPrincipal`, set `SecurityContextHolder`.
   - Invalid or expired: immediately return HTTP 401 `{"error":"Invalid or expired token"}`.
3. If no `Authorization` header is present, the filter passes through (Spring Security then rejects unauthenticated access downstream with 401/403).

---

## 4. Public Paths (No Token Required)

Defined in both `SecurityConfig.authorizeHttpRequests()` and `JwtAuthFilter.PUBLIC_PATHS`:

| Path | Purpose |
|------|---------|
| `GET /api/health` | Health probe |
| `GET /api/version` | Version info |
| `GET /actuator/**` | Spring Actuator |
| `GET /api/actuator/**` | Spring Actuator (proxy path) |
| `POST /api/auth/login` | Merchant account login |
| `POST /api/auth/logout` | Logout (client-side token discard) |
| `POST /api/admin/auth/login` | Platform admin login |
| `GET /api/user/channels/platforms` | Public platform list |
| `GET /api/enums/**` | Enum values (status labels, etc.) |

Everything else requires a valid JWT.

---

## 5. Spring Security Role Mapping

`UserPrincipal.getAuthorities()` maps the `role` claim to Spring authorities:

| JWT `role` value | Spring Authority granted |
|-----------------|--------------------------|
| `platform_admin` | `ROLE_PLATFORM_ADMIN`, `ROLE_USER` |
| `main` | `ROLE_MERCHANT_MAIN`, `ROLE_USER` |
| `sub` | `ROLE_USER` |

`SecurityConfig` uses these to restrict `/api/admin/**`:
```java
.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")
```
All other authenticated paths only require a valid token (`anyRequest().authenticated()`).

---

## 6. Using UserPrincipal in Controllers

Inject the authenticated principal via `@AuthenticationPrincipal`. All merchant-scoped queries **must** use `principal.getMerchantId()` — never trust a `merchantId` from the request body or query params.

```java
@GetMapping("/orders")
public ResponseEntity<?> getOrders(
        @AuthenticationPrincipal UserPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

    String merchantId = principal.getMerchantId();  // from JWT, not request
    Page<Order> orders = orderService.findByMerchantId(merchantId, page, size);
    return ResponseEntity.ok(orders);
}
```

Available `UserPrincipal` fields:

| Method | Type | Description |
|--------|------|-------------|
| `getAccountId()` | String | NanoID of the logged-in account |
| `getMerchantId()` | String | NanoID of the merchant (use for all DB queries) |
| `getEmail()` | String | Account email |
| `getName()` | String | Account display name |
| `getRole()` | String | `"platform_admin"` / `"main"` / `"sub"` |

---

## 7. Admin vs User Login Endpoints

There are two separate login paths that issue differently-scoped tokens:

| Path | Who uses it | `role` claim |
|------|-------------|--------------|
| `POST /api/auth/login` | Merchant operators | `"main"` or `"sub"` |
| `POST /api/admin/auth/login` | SimpleEC platform operators | `"platform_admin"` |

Platform admin tokens carry `ROLE_PLATFORM_ADMIN` and can access `/api/admin/**`. Merchant tokens cannot.

---

## 8. CORS Configuration

Allowed origins are configured via `simpleec.cors.allowed-origins` (comma-separated). Defaults:
```
http://localhost:8080, http://localhost:5173, http://localhost:3000
```

Allowed methods: `GET, POST, PUT, PATCH, DELETE, OPTIONS`
`allowCredentials: true` is set to support cookie-based flows if needed in the future.

In production, set this to your actual frontend domain(s) in `.env`:
```
CORS_ALLOWED_ORIGINS=https://app.yourcompany.com
```

---

## 9. Production Security Checklist

- [ ] `JWT_SECRET` must be a cryptographically random string of 32+ bytes. Generate with: `openssl rand -base64 48`
- [ ] Rotate `JWT_SECRET` immediately if it is ever exposed — all existing tokens become invalid
- [ ] Token is currently stored client-side (e.g., localStorage). This carries XSS risk. Mitigate with a strict Content Security Policy header
- [ ] No token refresh mechanism exists currently. After 7 days users must re-login
- [ ] `/actuator/**` exposes heap dumps and env vars — restrict to internal network or disable in production
- [ ] Review CORS `allowed-origins` — wildcard (`*`) must not be used with `allowCredentials: true`
