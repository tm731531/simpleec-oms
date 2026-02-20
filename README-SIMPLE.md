# a00000 商家 - 簡單CRUD操作指南

## 快速開始

所有操作都通過一個Bash腳本完成，無需Web界面。

### 查看數據

```bash
# 查看商家信息
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh merchant-info

# 查看所有帳號
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh account-list

# 查看所有產品
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh product-list

# 查看訂單
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh order-list

# 查看統計
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh stats
```

### 新增數據

```bash
# 新增帳號
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh account-add ACC0004 "帳號名" "email@a00000.com" "password123"

# 新增產品
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh product-add PROD004 SKU004 "產品名" 成本價 售價 庫存

# 例如：
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh product-add PROD004 SKU004 "滑鼠墊" 50 199 200
```

### 更新數據

```bash
# 更新產品庫存
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh product-update PROD001 40

# 更新商家信息
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh merchant-update merchant_name "新名稱"

# 更新訂單狀態
bash /home/tom/ONEEC/simpleec-oms/crud-tool.sh order-status <訂單ID> shipped
```

---

## 現有數據

### 商家
- **ID**: a00000
- **名稱**: 測試商家
- **郵箱**: merchant@example.com

### 帳號 (3個)
```
ACC0001  管理員    admin@a00000.com
ACC0002  編輯員    editor@a00000.com
ACC0003  檢視員    viewer@a00000.com
```

### 產品 (3個)
```
PROD001  SKU001  無線藍芽耳機   ¥500 → ¥1299   庫存: 50
PROD002  SKU002  智能手錶       ¥1200 → ¥2499  庫存: 30
PROD003  SKU003  USB-C充電線    ¥50 → ¥199     庫存: 100
```

---

## 命令列表

```
crud-tool.sh merchant-info
crud-tool.sh merchant-update <欄位> <值> [欄位 值]...

crud-tool.sh account-list
crud-tool.sh account-add <ID> <名稱> <郵箱> <密碼>

crud-tool.sh product-list [限制數]
crud-tool.sh product-add <ID> <SKU> <名稱> <成本> <價格> [數量]
crud-tool.sh product-update <ID> <數量>

crud-tool.sh order-list [狀態]
crud-tool.sh order-detail <訂單ID>
crud-tool.sh order-status <訂單ID> <新狀態>

crud-tool.sh stats
```

---

## 例子

```bash
# 新增一個「倉庫管理員」帳號
bash crud-tool.sh account-add ACC0005 "倉庫管理" "warehouse@a00000.com" "warehousepass"

# 新增一個產品
bash crud-tool.sh product-add PROD005 SKU005 "機械鍵盤" 400 1299 25

# 查看所有產品
bash crud-tool.sh product-list

# 更新產品庫存（賣出5個）
bash crud-tool.sh product-update PROD001 45

# 查看統計
bash crud-tool.sh stats
```

---

## 就是這麼簡單！

複製上面的任何命令，直接在終端執行即可。無需GUI，無需複雜設置。

**想要什麼就直接做什麼**。
