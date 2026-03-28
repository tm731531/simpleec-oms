# JWT 身份驗證

SimpleEC OMS 透過 Spring Security 使用無狀態 JWT（JSON Web Token）身份驗證。每個受保護的 API 請求都必須攜帶登入時取得的有效 Bearer token。

---

## 1. 登入流程

```
客戶端                              伺服器
  |                                   |
  |-- POST /api/auth/login ---------->|
  |   { email, password }            |
  |                                   |-- 從 DB 載入 Account
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
  |                                   |-- controller 方法執行，@AuthenticationPrincipal 可使用
  |<-- 200 OK ------------------------|
```

密碼雜湊使用 Spring Security 的 `BCryptPasswordEncoder`（BCrypt 演算法）。
Token 有效期預設為 604800 秒（7 天），由 `application.yml` 中的 `jwt.expiration` 控制。

---

## 2. Token 結構

JWT 由三個以點分隔的 Base64URL 編碼部分組成：`header.payload.signature`

### Header
```json
{
  "alg": "HS256",
  "typ": "JWT"
}
```

### Payload（Claims）
```json
{
  "sub": "acc_NANO_ID",        // accountId — 作為 subject 使用
  "merchantId": "mrc_NANO_ID", // 將所有 DB 查詢範圍限定至此商家
  "email": "user@example.com",
  "name": "王小明",
  "role": "main",              // "platform_admin" | "main" | "sub"
  "iat": 1743000000,           // 簽發時間（Unix epoch）
  "exp": 1743604800            // 到期時間（iat + 604800）
}
```

### Signature
`HMAC-SHA256(base64url(header) + "." + base64url(payload), JWT_SECRET)`

`JWT_SECRET` 從環境變數 `jwt.secret` 載入（在 `.env` 中設定，透過 `application.yml` 注入）。為滿足 HS256 最低要求，長度必須至少 32 bytes（256 bits）。

---

## 3. JwtAuthFilter 行為

`JwtAuthFilter` 繼承 `OncePerRequestFilter`，在 `UsernamePasswordAuthenticationFilter` 之前執行。

**對公開路徑**（見第 4 節）：`shouldNotFilter()` 回傳 `true`——完全跳過 filter。

**對受保護路徑：**
1. 讀取 `Authorization` header；提取 `Bearer ` 前綴後的 token。
2. 呼叫 `JwtUtil.isValid(token)`。
   - 有效：解析 claims、建構 `UserPrincipal`、設定 `SecurityContextHolder`。
   - 無效或已過期：立即回傳 HTTP 401 `{"error":"Invalid or expired token"}`。
3. 若沒有 `Authorization` header，filter 放行（Spring Security 隨後以 401/403 拒絕未驗證的請求）。

---

## 4. 公開路徑（不需要 Token）

在 `SecurityConfig.authorizeHttpRequests()` 和 `JwtAuthFilter.PUBLIC_PATHS` 中均有定義：

| 路徑 | 用途 |
|------|---------|
| `GET /api/health` | 健康探針 |
| `GET /api/version` | 版本資訊 |
| `GET /actuator/**` | Spring Actuator |
| `GET /api/actuator/**` | Spring Actuator（代理路徑） |
| `POST /api/auth/login` | 商家帳號登入 |
| `POST /api/auth/logout` | 登出（客戶端丟棄 token） |
| `POST /api/admin/auth/login` | 平台管理員登入 |
| `GET /api/user/channels/platforms` | 公開平台清單 |
| `GET /api/enums/**` | Enum 值（狀態標籤等） |

其餘所有路徑均需要有效的 JWT。

---

## 5. Spring Security 角色對應

`UserPrincipal.getAuthorities()` 將 `role` claim 對應為 Spring authorities：

| JWT `role` 值 | 授予的 Spring Authority |
|-----------------|--------------------------|
| `platform_admin` | `ROLE_PLATFORM_ADMIN`、`ROLE_USER` |
| `main` | `ROLE_MERCHANT_MAIN`、`ROLE_USER` |
| `sub` | `ROLE_USER` |

`SecurityConfig` 使用這些 authority 限制 `/api/admin/**`：
```java
.requestMatchers("/api/admin/**").hasAuthority("ROLE_PLATFORM_ADMIN")
```
其他所有已驗證路徑只需有效 token（`anyRequest().authenticated()`）。

---

## 6. 在 Controller 中使用 UserPrincipal

透過 `@AuthenticationPrincipal` 注入已驗證的 principal。所有商家範圍的查詢**必須**使用 `principal.getMerchantId()`——切勿信任來自 request body 或 query params 的 `merchantId`。

```java
@GetMapping("/orders")
public ResponseEntity<?> getOrders(
        @AuthenticationPrincipal UserPrincipal principal,
        @RequestParam(defaultValue = "0") int page,
        @RequestParam(defaultValue = "20") int size) {

    String merchantId = principal.getMerchantId();  // 來自 JWT，非 request
    Page<Order> orders = orderService.findByMerchantId(merchantId, page, size);
    return ResponseEntity.ok(orders);
}
```

`UserPrincipal` 可用欄位：

| 方法 | 型別 | 說明 |
|--------|------|-------------|
| `getAccountId()` | String | 已登入帳號的 NanoID |
| `getMerchantId()` | String | 商家的 NanoID（用於所有 DB 查詢） |
| `getEmail()` | String | 帳號 email |
| `getName()` | String | 帳號顯示名稱 |
| `getRole()` | String | `"platform_admin"` / `"main"` / `"sub"` |

---

## 7. 管理員 vs 使用者登入端點

有兩個獨立的登入路徑，各自簽發不同範圍的 token：

| 路徑 | 使用者 | `role` claim |
|------|-------------|--------------|
| `POST /api/auth/login` | 商家操作員 | `"main"` 或 `"sub"` |
| `POST /api/admin/auth/login` | SimpleEC 平台操作員 | `"platform_admin"` |

平台管理員 token 具有 `ROLE_PLATFORM_ADMIN`，可存取 `/api/admin/**`。商家 token 無此權限。

---

## 8. CORS 設定

允許的 origin 透過 `simpleec.cors.allowed-origins` 設定（逗號分隔）。預設值：
```
http://localhost:8080, http://localhost:5173, http://localhost:3000
```

允許的方法：`GET, POST, PUT, PATCH, DELETE, OPTIONS`
設定 `allowCredentials: true` 以支援未來可能需要的 cookie 流程。

在生產環境中，於 `.env` 中設定實際的前端網域：
```
CORS_ALLOWED_ORIGINS=https://app.yourcompany.com
```

---

## 9. 生產環境安全檢查清單

- [ ] `JWT_SECRET` 必須是 32+ bytes 的密碼學隨機字串。產生方式：`openssl rand -base64 48`
- [ ] 若 `JWT_SECRET` 曾外洩，立即輪替——所有現有 token 將失效
- [ ] Token 目前以客戶端方式儲存（如 localStorage），存在 XSS 風險。透過嚴格的 Content Security Policy header 降低風險
- [ ] 目前無 token 刷新機制。7 天後使用者必須重新登入
- [ ] `/actuator/**` 會暴露 heap dump 和環境變數——在生產環境中限制為內部網路存取或停用
- [ ] 檢查 CORS `allowed-origins`——使用 `allowCredentials: true` 時不得使用萬用字元（`*`）
