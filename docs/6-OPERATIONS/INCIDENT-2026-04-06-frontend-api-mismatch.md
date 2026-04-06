# Incident Report — 2026-04-06
## 前端程式碼遺失與全面 API 對接失效

**嚴重等級：CRITICAL**
**影響範圍：整個 user-app 前端功能全數失效**
**持續時間：本日會話全程（數小時）**

---

## 一、事件摘要

本次事件包含三個獨立但相關的嚴重失誤，共同導致整個前端無法正常運作：

1. **前端原始碼完全遺失，Claude 未察覺**
2. **重建程式碼時完全忽略現有後端系統的 API 規格**
3. **審視團隊品質不足，未在部署前攔截問題**

---

## 二、根因分析

### 失誤 1：前端原始碼遺失卻未被察覺

**發生經過：**
- `user-app/` 原本是一個 git submodule，但 `.gitmodules` 從未提交到任何分支
- submodule 的 remote URL 沒有記錄，等同於這段程式碼只存在於原始工作環境
- 在某次環境操作後，submodule 目錄變成空的，22 個 `.vue`/`.ts` 檔案全部消失
- Claude **沒有主動發現**這個問題，而是繼續在空目錄上工作

**為什麼沒發現：**
- 每次只看「需要修改的檔案」，沒有做整體 inventory 檢查
- 沒有將「目錄下的實際檔案清單」與「應有的檔案清單」做對比
- 沒有在開始工作前確認 git submodule 狀態（`git submodule status`）

**正確的做法：**
- 接手任何前端任務前，先確認 `src/views/`、`src/components/`、`src/api/` 的檔案數量是否合理
- 若發現目錄近乎空的，立刻停下來告知用戶，不要直接「重建」

---

### 失誤 2：重建時完全不管現有 API，想寫什麼就寫什麼

**發生經過：**
恢復程式碼時，Claude 沒有先讀後端 controller，而是根據「直覺」寫了以下錯誤：

| 問題 | 寫了什麼 | 正確應該是 |
|------|---------|-----------|
| 登入路徑 | `/api/user/login` | `/api/auth/login` |
| SellPack URL | `/api/user/sell-packs` | `/api/user/sellpacks` |
| Channel platforms | `/api/user/platforms` | `/api/user/channels/platforms` |
| Order 分頁 | `page=0, size=` | `page=1, pageSize=` (1-indexed) |
| Product 分頁 | `page=1, pageSize=` | `page=0, size=` (0-indexed) |
| Response 解析 | `.data.content` / `.data.totalElements` | `.data` / `.pagination.total` |
| Ship 操作 | `POST /orders/{id}/ship` | `PATCH /orders/{id}` body `{action:"ship"}` |
| Token localStorage key | `token` (3 種不同 key) | `authToken` (統一) |

**為什麼發生：**
- **沒有在動手之前先 curl 真實 API**，驗證 endpoint 存在且回傳正確格式
- **沒有先讀 controller 原始碼**，就憑印象寫 API 客戶端
- **沒有先讀現有 `api/index.ts`**，就新建 axios 呼叫，造成 token key 不一致

**正確的做法（前端重建 SOP）：**
```
1. 列出所有後端 Controller 的 @RequestMapping
2. 對每個需要的 endpoint，讀取 Controller 確認：
   - URL 完整路徑
   - Request params 名稱與型別
   - Response 包裝格式（UserPageResponse? 還是 raw List?）
   - 分頁是 0-indexed 還是 1-indexed
3. 用 curl + 真實 token 驗證 response 結構
4. 才開始寫前端 API 檔案
```

---

### 失誤 3：審視團隊品質不足

**發生經過：**
- 本次派出的「審視團隊」在問題已經進入生產環境、用戶回報失效後才啟動
- 應該在部署前就做對比，不是部署後補救
- 第一次的「全端工程師」agent 修正後仍留下 token key 不一致（`authToken` vs `token`）需要人工再修

**正確的做法：**
- 任何前端重建完成後，在 `npm run build` 之前，必須有一個審視步驟：
  - 對照後端 controller，逐一驗證每個 API 呼叫的 URL、params、response 解析
- 審視應在部署前完成，不是在用戶回報問題後才做

---

## 三、時間線

| 時間 | 事件 |
|------|------|
| 會話開始 | 用戶回報訂單 item 欄位錯誤、通路功能消失 |
| 早期 | 發現 `user-app/src/` 下大量檔案遺失 |
| 中期 | 從 `dev:docs/archive/plans/` 恢復原始碼（22 個檔案） |
| 恢復後 | 部署 → 用戶回報「帳密不見了、資料都空白」|
| 診斷 | 發現登入 403、資料全 500/401 |
| 修復 1 | 修正 `/api/auth/login` 路徑 |
| 修復 2 | 修正 token key 不一致 |
| 修復 3 | 修正 Order 500（page index 問題） |
| 修復 4 | 修正 Channel 404（錯誤 URL） |
| 用戶要求 | 起審視團隊做全面比對 |
| 架構師 agent | 輸出完整 API contract 對照表 |
| 全端工程師 agent | 修正 8 個 API 檔、5 個 components、4 個 views |
| 最終修正 | token key 再次統一（authToken） |
| 部署完成 | 系統恢復正常 |

---

## 四、影響

- 整個 user-app 在恢復後完全無法使用（無法登入、所有資料頁空白）
- 用戶需要多次反映才觸發修復
- 浪費大量會話時間在本可避免的問題上

---

## 五、預防措施（未來必須執行）

### A. 開始任何前端任務前（強制檢查清單）

```
□ git submodule status — 確認 submodule 存在且不是空的
□ ls src/views/ src/components/ src/api/ — 確認檔案數量合理
□ 若有疑問，先告知用戶，不要擅自重建
```

### B. 前端重建或新增 API 呼叫前（強制）

```
□ 讀取對應的後端 Controller 原始碼
□ 確認 URL 路徑（包含 @RequestMapping 前綴）
□ 確認 params 名稱（page? pageSize? size?）
□ 確認分頁方向（0-indexed 還是 1-indexed）
□ 確認 Response wrapper（UserPageResponse? raw List? flat object?）
□ curl 真實 API 驗證一次
□ 讀取現有 api/index.ts 確認 axios 實例和 interceptor 設定
```

### C. 部署前審視（強制）

```
□ 對每個新增或修改的 API 呼叫，確認對應 controller 存在
□ npm run build 通過
□ 確認所有 localStorage key 在 auth store / interceptor / router / App.vue 一致
```

---

## 六、已知後端 API 不一致（技術債）

後端 controller 分頁規則不統一，這是後端技術債，前端必須按實際情況適配：

| Controller | page 起始 | size param 名稱 |
|-----------|----------|----------------|
| Orders, Refunds, Shipments, Inventory | **1** | `pageSize` |
| Products, SellPacks | **0** | `size` |

建議後續版本統一後端為 1-indexed + `pageSize`，前端才能用一致邏輯。

---

*記錄者：Claude Sonnet 4.6*
*日期：2026-04-06*
