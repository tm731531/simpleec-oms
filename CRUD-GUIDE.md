# SimpleEC OMS CRUD 工具使用指南

## 概述

簡單的命令行CRUD工具，用於管理 a00000 商家的所有數據。

**位置:** `/home/tom/ONEEC/simpleec-oms/crud-tool.sh`

---

## 快速開始

```bash
# 查看商家信息
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh merchant-info

# 查看所有帳號
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh account-list

# 查看產品列表
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh product-list

# 查看統計數據
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh stats
```

---

## 詳細命令說明

### 📋 商家 (Merchant)

#### 查看商家信息
```bash
bash crud-tool.sh merchant-info
```
顯示：a00000 商家的完整信息（名稱、郵箱、電話、地址等）

#### 更新商家信息
```bash
bash crud-tool.sh merchant-update merchant_name "新商家名稱"
bash crud-tool.sh merchant-update address_city "台中" address_region "台中市"
```

允許更新的欄位：
- `merchant_name` - 商家名稱
- `merchant_email` - 郵箱
- `merchant_phone_number` - 電話
- `address_city` - 城市
- `address_region` - 地區
- `address_line1` - 地址第一行

---

### 👤 帳號 (Account)

#### 列出所有帳號
```bash
bash crud-tool.sh account-list
```

顯示結果示例：
```
   id    | account_name |   account_email   | access_level | status | created_at
---------+--------------+-------------------+--------------+--------+------------
 ACC0001 | 管理員       | admin@a00000.com  |          999 | enable | 2026-02-20
 ACC0002 | 編輯員       | editor@a00000.com |            0 | enable | 2026-02-20
```

#### 新增帳號
```bash
bash crud-tool.sh account-add ACC0004 "營運員" "ops@a00000.com" "password123"
```

參數：
- `ACC0004` - 帳號 ID（唯一）
- `營運員` - 帳號名稱
- `ops@a00000.com` - 郵箱地址
- `password123` - 密碼

訪問級別（access_level）：
- `0` - 一般員工
- `1` - 編輯員
- `999` - 管理員

---

### 📦 產品 (Product)

#### 列出所有產品
```bash
bash crud-tool.sh product-list          # 列出前 10 筆
bash crud-tool.sh product-list 20       # 列出前 20 筆
```

顯示結果示例：
```
   id    |  sku   |     name     | cost_price | suggest_price | quantity | status
---------+--------+--------------+------------+---------------+----------+--------
 PROD001 | SKU001 | 無線藍芽耳機 |     500.00 |       1299.00 |       50 | active
 PROD002 | SKU002 | 智能手錶     |    1200.00 |       2499.00 |       30 | active
```

#### 新增產品
```bash
bash crud-tool.sh product-add PROD004 SKU004 "滑鼠墊" 50 199 200
```

參數：
- `PROD004` - 產品 ID
- `SKU004` - SKU 碼
- `滑鼠墊` - 產品名稱
- `50` - 成本價
- `199` - 建議售價
- `200` - 初始庫存（可選，預設 0）

#### 更新庫存
```bash
bash crud-tool.sh product-update PROD001 45  # 更新 PROD001 的庫存為 45
```

---

### 📋 訂單 (Order)

#### 列出訂單
```bash
bash crud-tool.sh order-list             # 列出所有訂單
bash crud-tool.sh order-list pending     # 列出待處理訂單
bash crud-tool.sh order-list completed   # 列出已完成訂單
```

訂單狀態：
- `pending` - 待處理
- `confirmed` - 已確認
- `shipped` - 已出貨
- `completed` - 已完成
- `cancelled` - 已取消

#### 查看訂單詳情
```bash
bash crud-tool.sh order-detail ORD20260220001
```

#### 更新訂單狀態
```bash
bash crud-tool.sh order-status ORD20260220001 shipped
```

---

### 📊 統計 (Statistics)

#### 查看統計數據
```bash
bash crud-tool.sh stats
```

顯示結果：
```
    類別    | 數值
------------+------
 產品總數   | 3
 訂單總數   | 5
 帳號總數   | 3
 待處理訂單 | 2

訂單狀態分佈:
 狀態      | 數量
-----------+------
 pending   | 2
 completed | 3
```

---

## 實際使用案例

### 案例 1：新增一個新編輯員帳號
```bash
bash crud-tool.sh account-add ACC0005 "產品編輯" "product-editor@a00000.com" "secure123"
```

### 案例 2：新增產品並檢查庫存
```bash
bash crud-tool.sh product-add PROD005 SKU005 "鍵盤" 300 899 75
bash crud-tool.sh product-list
```

### 案例 3：更新商家信息
```bash
bash crud-tool.sh merchant-update merchant_email "newemail@a00000.com"
bash crud-tool.sh merchant-info
```

### 案例 4：調整產品庫存
```bash
bash crud-tool.sh product-update PROD001 40  # 銷售後庫存更新
bash crud-tool.sh product-list
```

### 案例 5：查看訂單統計
```bash
bash crud-tool.sh stats
bash crud-tool.sh order-list pending
```

---

## 資料庫連線信息

```
主機: localhost
端口: 5433
用戶: simpleec
密碼: simpleec123
資料庫: simpleec
```

所有命令都自動使用這些憑證連接，無需手動輸入。

---

## 注意事項

1. **ID 唯一性** - 帳號 ID、產品 ID 等必須唯一，不能重複
2. **郵箱唯一性** - 帳號郵箱必須唯一
3. **商家綁定** - 所有數據都自動綁定到 `a00000` 商家
4. **狀態值** - 只能使用預定義的狀態值（active, enable, pending, confirmed 等）
5. **數字格式** - 價格和數量必須是有效的數字

---

## 常見問題

**Q: 如何重設帳號密碼？**
A: 目前工具不支持直接修改密碼，可以直接修改資料庫：
```bash
PGPASSWORD=simpleec123 psql -h localhost -p 5433 -U simpleec -d simpleec \
  -c "UPDATE account SET account_password = 'newhash' WHERE id = 'ACC0001'"
```

**Q: 如何刪除產品？**
A: 目前工具不支持刪除，可以改為停用：
```bash
PGPASSWORD=simpleec123 psql -h localhost -p 5433 -U simpleec -d simpleec \
  -c "UPDATE product SET status = 'inactive' WHERE id = 'PROD001'"
```

**Q: 訂單來自哪裡？**
A: 訂單通常由 Kafka channel jobs 從 MOMO、Shopee、Yahoo、PChome 等平台自動同步到資料庫。

---

## 相關資源

- **資料庫路徑**: `/home/tom/ONEEC/simpleec-oms/`
- **資料庫schema**: `/home/tom/ONEEC/simpleec-oms/docker/init-db/01-schema.sql`
- **測試數據**: 預定義的 a00000 商家 + 3 個帳號 + 3 個產品

---

**最後更新**: 2026-02-21
**工具版本**: 1.0
