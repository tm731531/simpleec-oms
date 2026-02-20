# SimpleEC OMS 双界面设计文档

> **Admin + User 微前端架构设计**
> 更新日期：2026-02-21
> 版本：1.0

---

## 📌 设计目标

构建两个独立的前端应用，通过微前端架构隔离，共享同一个数据库和后端API：
1. **Admin界面** - 内部运营平台，管理商家、账户、通路等基础数据
2. **User界面** - 客户门户，账户登入后管理商品、订单、物流等业务数据

---

## 🏗️ 第一部分：整体架构

### 1.1 微前端架构图

```
┌─────────────────────────────────────────────────────────┐
│                   容器应用（qiankun）                      │
│  http://localhost:8080/                                 │
│  ┌──────────────────┐         ┌──────────────────────┐  │
│  │   Admin应用      │         │   User应用           │  │
│  │  /admin/*        │         │  /app/*              │  │
│  │  (沙箱隔离)      │         │  (沙箱隔离)          │  │
│  └──────────┬───────┘         └────────────┬─────────┘  │
│             │ 共享事件总线                  │             │
│             └────────┬────────────────────┘             │
└──────────────────────┼───────────────────────────────────┘
                       │
            ┌──────────┴──────────┐
            │   Spring Boot API   │
            │   (port 8082)       │
            │   已有端点：        │
            │   - /api/product    │
            │   - /api/orders     │
            │   - /api/stats      │
            │   需扩展：Admin/User │
            │   专用端点          │
            └──────────┬──────────┘
                       │
              PostgreSQL (port 5433)
              (merchant, account, platform, ...)
```

### 1.2 关键特性

| 特性 | 说明 |
|------|------|
| **完全隔离** | Admin和User各自编译、部署、路由独立 |
| **共享资源** | 数据库、后端API、可选的UI组件库 |
| **通信方式** | 事件总线（qiankun提供） |
| **认证** | Admin：预设允许；User：JWT登入 |
| **部署** | 可独立更新，容器框架动态加载 |

---

## 👨‍💼 第二部分：Admin界面设计

### 2.1 界面结构

Admin是**内部运营平台**，URL不公开，预设允许进入。主要掌控三个核心表的数据。

```
Admin应用 (/admin)
├─ 首页仪表板
│  ├─ 系统统计
│  ├─ 最近操作记录
│  └─ 系统告警
│
├─ 商家管理 (/merchant)
│  ├─ 商家列表（分页）
│  ├─ 新增商家
│  ├─ 编辑商家
│  └─ 删除商家
│
├─ 账户管理 (/account)
│  ├─ 账户列表（分页）
│  ├─ 新增账户
│  ├─ 编辑账户/权限
│  ├─ 重置密码
│  └─ 禁用/删除账户
│
├─ 通路管理 (/platform)
│  ├─ 通路列表（分页）
│  ├─ 编辑通路配置
│  └─ 启用/禁用通路
│
└─ 系统监控 (/monitor)
   ├─ 数据统计（daily_statistics）
   ├─ 同步日志（分页）
   ├─ 失败日志（分页）
   └─ API版本追踪
```

### 2.2 核心表 CRUD 操作

#### **merchant 表**

| 字段 | 操作 | 类型 | 说明 |
|------|------|------|------|
| id | - | PK | 主键，创建后不可改 |
| merchant_name | CRUD | varchar | 商家名称 |
| merchant_email | CRUD | varchar | 商家邮箱 |
| merchant_phone_number | CRUD | varchar | 商家电话 |
| tax_id_number | CRUD | varchar | 税号 |
| address_* | CRUD | varchar | 完整地址信息（city, region, country, zip, line1, line2, phone） |
| vip_level | CRUD | int | VIP等级 |
| user_local_time_zone | CRUD | varchar | 时区 |
| payer_* | CRUD | varchar | 付款人信息（name, email, phone） |
| status | CRUD | varchar | 状态（active/inactive） |

#### **account 表**

| 字段 | 操作 | 类型 | 说明 |
|------|------|------|------|
| id | - | PK | 主键 |
| account_name | CRUD | varchar | 账户名 |
| account_email | CRUD | varchar | 邮箱（唯一） |
| account_password | CU | varchar | 密码（创建/重置） |
| account_tel | CRUD | varchar | 电话 |
| is_main_account | CRUD | boolean | 是否主账户 |
| access_level | CRUD | int | 权限级别（0=普通, 999=管理员） |
| merchant_id | C | varchar | 所属商家（创建时指定，不可改） |
| status | CRUD | varchar | 状态（enable/disable） |
| totp_secret | CRUD | varchar | 2FA密钥（可重置） |

#### **platform 表**

| 字段 | 操作 | 类型 | 说明 |
|------|------|------|------|
| id | - | PK | 平台ID（MOMO、SHOPEE等） |
| platform_name | CRUD | varchar | 平台显示名称 |
| credential1 | CRUD | varchar(4096) | API密钥/Token（敏感） |
| credential2 | CRUD | varchar(4096) | 备用密钥（敏感） |
| actived | CRUD | boolean | 是否启用 |
| queue_topic | CRUD | varchar | Kafka主题 |
| currency | CRUD | varchar | 币种（默认TWD） |
| ship_options | CRUD | json | 物流配置 |

### 2.3 系统监控

- **统计数据** - 展示 daily_statistics 表的关键指标
- **同步日志** - channel_sync_logs 表，分页展示
- **失败日志** - failed_task_logs 表，分页展示
- **API版本** - channel_api_versions 表，只读

---

## 👥 第三部分：User界面设计

### 3.1 界面结构与优先级

User是**客户门户**，需要账户登入。用户登入后可以看到所属商家的所有数据。

优先级顺序：统计 → 商品 → 通路 → 赛场 → 订单 → 物流 → 退货

```
User应用 (/app)
├─ 登入页面 (/login)
│  └─ Email + Password登入，返回JWT token
│
├─ 首页仪表板 (/dashboard)  【优先级1】
│  ├─ 关键统计（daily_statistics）
│  │  ├─ 今日订单数、营收、待处理、退货
│  │  ├─ 订单趋势图表
│  │  └─ 通路销售分布
│  └─ 最近订单和告警
│
├─ 商品管理 (/product)     【优先级2】
│  ├─ 商品列表（分页、搜索、筛选）
│  │  └─ 字段：ID、名称、分类、价格、库存、状态
│  ├─ 新增商品
│  ├─ 编辑商品信息
│  ├─ 管理SKU（product_spec）
│  ├─ 管理条码（product_barcode）
│  └─ 删除商品
│
├─ 通路配置 (/channel)     【优先级3】
│  ├─ 绑定的通路列表（分页）
│  │  └─ 字段：ID、平台名、状态、API版本
│  ├─ 通路账户配置（platform_account）
│  ├─ 编辑通路设置
│  ├─ 同步日志（分页、只读）
│  └─ API版本信息（只读）
│
├─ 赛场管理 (/sellpack)    【优先级4】
│  ├─ 赛场列表（分页）
│  │  └─ 字段：商品、通路、价格、库存、状态
│  ├─ 新增赛场（商品 → 通路 → 参数设置）
│  ├─ 编辑赛场（价格、库存、通路参数）
│  └─ 下架商品
│
├─ 订单管理 (/order)       【优先级5】
│  ├─ 订单列表（分页、按状态筛选）
│  │  ├─ 待处理、已确认、已发货、已完成、退货中
│  │  └─ 字段：订单ID、通路、买家、金额、状态
│  ├─ 订单详情
│  │  ├─ 基本信息（买家、地址、支付状态）
│  │  ├─ 订单项列表
│  │  ├─ 物流信息
│  │  └─ 备注
│  ├─ 更新订单状态
│  ├─ 添加备注
│  └─ 打印订单/面单
│
├─ 物流管理 (/shipment)    【优先级6】
│  ├─ 物流记录列表（分页）
│  ├─ 物流追踪信息
│  ├─ 配置物流信息
│  └─ 物流状态日志
│
└─ 退货管理 (/refund)      【优先级7】
   ├─ 退货单列表（分页）
   ├─ 退货详情（refund_order_items）
   ├─ 处理退货流程
   ├─ 退货统计
   └─ 退款状态
```

### 3.2 User的数据操作

| 优先级 | 表 | 操作 | 说明 |
|--------|-----|------|------|
| 1 | daily_statistics | R | 统计数据，只读 |
| 2 | product | CRUD | 完整CRUD |
| 2 | product_spec | CRUD | SKU管理 |
| 2 | product_barcode | CRUD | 条码管理 |
| 3 | channel | RU | 查看、编辑配置 |
| 3 | platform_account | RU | 账户配置 |
| 3 | channel_sync_logs | R | 同步日志，只读 |
| 3 | channel_api_versions | R | API版本，只读 |
| 4 | sell_pack | CRUD | 完整CRUD |
| 5 | orders | RU | 查看、更新状态 |
| 5 | order_status_logs | R | 状态日志，只读 |
| 6 | order_shipments | CRUD | 物流信息 |
| 7 | refund_orders | RU | 查看、处理 |
| 7 | refund_order_items | RU | 退货项管理 |

---

## 🔄 第四部分：实时刷新机制

### 4.1 轮询策略

所有列表界面采用**轮询机制**实现实时更新：
- **默认刷新间隔**：5分钟
- **用户控制**：手动刷新按钮 + 暂停/继续按钮
- **显示**：显示"最后刷新时间"

### 4.2 UI控制元素

```
┌─────────────────────────────────────────────┐
│ [🔄 刷新] [⏸ 暂停] 最后刷新: 2分钟前        │
└─────────────────────────────────────────────┘
│                                             │
│  订单列表（自动5分钟刷新一次）               │
│  ┌──────────────────────────────────────┐  │
│  │ ID    | 通路  | 买家  | 金额 | 状态  │  │
│  │ ORD01 | MOMO | 张三  | $50 | 待处理 │  │
│  │ ...                                  │  │
│  └──────────────────────────────────────┘  │
│                                             │
│ 分页: [< 1 2 3 ... >]                      │
└─────────────────────────────────────────────┘
```

### 4.3 实现细节

**轮询流程**：
1. 页面初始化时启动5分钟轮询定时器
2. 定时器触发时调用API获取最新数据
3. 对比上次数据，如有变化则更新UI
4. 更新"最后刷新时间"
5. 用户可随时点击[刷新]手动触发，或点击[⏸ 暂停]停止轮询
6. 页面卸载时清理定时器

**技术实现**：
- Vue3：`onMounted` 启动轮询，`onUnmounted` 清理
- `setInterval()` 5分钟轮询
- `ref` 追踪数据变化
- 使用深度对比检测数据变化
- 可选：Toast通知（新订单、状态变更）

---

## 🛠️ 第五部分：技术栈

### 5.1 前端技术栈

| 层级 | 技术 | 版本 | 说明 |
|------|------|------|------|
| **微前端** | qiankun | ^2.8 | 应用隔离、沙箱、共享状态 |
| **框架** | Vue | 3 | 反应式、组合式API |
| **构建** | Vite | 5 | 快速开发、优化产生 |
| **UI库** | Element Plus | 2 | 企业级组件库 |
| **状态** | Pinia | 2 | 轻量状态管理 |
| **HTTP** | axios | 1 | API请求 |
| **路由** | Vue Router | 4 | 单应用路由 |
| **图表** | ECharts | 5 | 统计图表 |
| **验证** | vee-validate | 4 | 表单验证 |
| **日期** | dayjs | 1 | 时间处理 |
| **语言** | TypeScript | 5 | 类型安全 |

### 5.2 项目结构

```
simpleec-oms/
├── micro-frontend-container/          # 微前端容器
│   ├── src/
│   │   ├── main.ts
│   │   ├── registerMicroApps.ts       # 注册Admin和User
│   │   ├── sharedState.ts             # 共享状态总线
│   │   └── App.vue
│   ├── vite.config.ts
│   └── package.json
│
├── admin-app/                          # Admin微应用
│   ├── src/
│   │   ├── main.ts
│   │   ├── views/
│   │   │   ├── DashboardPage.vue
│   │   │   ├── MerchantPage.vue
│   │   │   ├── AccountPage.vue
│   │   │   ├── PlatformPage.vue
│   │   │   └── MonitorPage.vue
│   │   ├── components/
│   │   │   ├── MerchantTable.vue
│   │   │   ├── MerchantForm.vue
│   │   │   ├── AccountTable.vue
│   │   │   ├── PlatformTable.vue
│   │   │   └── RefreshControls.vue   # 刷新+暂停控件
│   │   ├── api/
│   │   │   ├── merchant.ts
│   │   │   ├── account.ts
│   │   │   ├── platform.ts
│   │   │   └── monitor.ts
│   │   ├── stores/
│   │   │   ├── admin.ts              # Admin全局状态
│   │   │   └── polling.ts            # 轮询状态
│   │   └── hooks/
│   │       └── usePolling.ts         # 轮询自定义Hook
│   ├── vite.config.ts
│   └── package.json
│
├── user-app/                           # User微应用
│   ├── src/
│   │   ├── main.ts
│   │   ├── views/
│   │   │   ├── LoginPage.vue
│   │   │   ├── DashboardPage.vue
│   │   │   ├── ProductPage.vue
│   │   │   ├── ChannelPage.vue
│   │   │   ├── SellPackPage.vue
│   │   │   ├── OrderPage.vue
│   │   │   ├── ShipmentPage.vue
│   │   │   └── RefundPage.vue
│   │   ├── components/
│   │   │   ├── ProductTable.vue
│   │   │   ├── ProductForm.vue
│   │   │   ├── OrderTable.vue
│   │   │   ├── OrderDetail.vue
│   │   │   ├── Pagination.vue        # 分页组件
│   │   │   └── RefreshControls.vue   # 刷新+暂停控件
│   │   ├── api/
│   │   │   ├── auth.ts               # 登入、JWT
│   │   │   ├── product.ts
│   │   │   ├── order.ts
│   │   │   ├── channel.ts
│   │   │   ├── sellpack.ts
│   │   │   ├── shipment.ts
│   │   │   ├── refund.ts
│   │   │   └── dashboard.ts
│   │   ├── stores/
│   │   │   ├── auth.ts               # 认证状态（JWT、用户）
│   │   │   ├── user.ts               # 用户数据状态
│   │   │   └── polling.ts            # 轮询状态
│   │   ├── hooks/
│   │   │   ├── usePolling.ts         # 轮询自定义Hook
│   │   │   └── usePagination.ts      # 分页自定义Hook
│   │   ├── middleware/
│   │   │   └── authMiddleware.ts     # JWT验证中间件
│   │   └── types/
│   │       └── index.ts              # TypeScript类型
│   ├── vite.config.ts
│   └── package.json
│
└── shared-lib/                         # 共享库（可选）
    ├── components/
    │   ├── DataTable.vue             # 通用表格
    │   ├── PaginationBar.vue         # 通用分页
    │   ├── RefreshBar.vue            # 通用刷新条
    │   └── Modal.vue                 # 通用弹窗
    ├── utils/
    │   ├── api.ts                    # API客户端配置
    │   ├── validators.ts             # 验证器
    │   ├── formatters.ts             # 格式化
    │   └── constants.ts              # 常量
    ├── types/
    │   ├── models.ts                 # 数据模型
    │   └── api.ts                    # API类型
    ├── styles/
    │   └── common.css                # 公共样式
    └── package.json
```

### 5.3 后端API扩展要求

现有Spring Boot API（port 8082）需要扩展：

#### **Admin专用端点** (`/api/admin/`)

```
POST   /api/admin/merchant              # 新增商家
GET    /api/admin/merchant              # 列表（分页）
GET    /api/admin/merchant/{id}         # 详情
PUT    /api/admin/merchant/{id}         # 编辑
DELETE /api/admin/merchant/{id}         # 删除

POST   /api/admin/account               # 新增账户
GET    /api/admin/account               # 列表（分页）
GET    /api/admin/account/{id}          # 详情
PUT    /api/admin/account/{id}          # 编辑
DELETE /api/admin/account/{id}          # 删除
POST   /api/admin/account/{id}/reset-password  # 重置密码

POST   /api/admin/platform              # 新增平台
GET    /api/admin/platform              # 列表（分页）
GET    /api/admin/platform/{id}         # 详情
PUT    /api/admin/platform/{id}         # 编辑
DELETE /api/admin/platform/{id}         # 删除

GET    /api/admin/monitor/statistics    # 统计数据
GET    /api/admin/monitor/logs          # 同步日志（分页）
GET    /api/admin/monitor/failed-logs   # 失败日志（分页）
GET    /api/admin/monitor/api-versions  # API版本
```

#### **User专用端点** (`/api/user/`) - 需JWT认证

```
POST   /api/user/login                  # 登入（返回JWT）
GET    /api/user/profile                # 当前用户信息

GET    /api/user/dashboard              # 仪表板统计
GET    /api/user/products               # 商品列表（分页）
POST   /api/user/products               # 新增商品
GET    /api/user/products/{id}          # 商品详情
PUT    /api/user/products/{id}          # 编辑商品
DELETE /api/user/products/{id}          # 删除商品

GET    /api/user/product-specs/{productId}    # SKU列表
POST   /api/user/product-specs                # 新增SKU
PUT    /api/user/product-specs/{id}          # 编辑SKU
DELETE /api/user/product-specs/{id}          # 删除SKU

GET    /api/user/channels               # 通路列表
PUT    /api/user/channels/{id}          # 编辑通路
GET    /api/user/channels/{id}/sync-logs     # 同步日志

GET    /api/user/sellpacks              # 赛场列表（分页）
POST   /api/user/sellpacks              # 新增赛场
PUT    /api/user/sellpacks/{id}         # 编辑赛场
DELETE /api/user/sellpacks/{id}         # 删除赛场

GET    /api/user/orders                 # 订单列表（分页）
GET    /api/user/orders/{id}            # 订单详情
PUT    /api/user/orders/{id}/status     # 更新订单状态
PUT    /api/user/orders/{id}/remark     # 更新备注

GET    /api/user/shipments              # 物流列表（分页）
POST   /api/user/shipments              # 新增物流记录
PUT    /api/user/shipments/{id}         # 编辑物流

GET    /api/user/refunds                # 退货列表（分页）
GET    /api/user/refunds/{id}           # 退货详情
PUT    /api/user/refunds/{id}           # 处理退货
```

#### **分页参数规范**

所有分页端点支持查询参数：
```
GET /api/.../list?page=1&pageSize=20&sortBy=created_at&order=desc&search=keyword
```

响应格式：
```json
{
  "code": 200,
  "data": {
    "total": 1000,
    "page": 1,
    "pageSize": 20,
    "totalPages": 50,
    "items": [...]
  },
  "message": "success"
}
```

#### **JWT认证**

User界面的所有请求需要在Header中携带：
```
Authorization: Bearer <jwt_token>
```

---

## 📋 第六部分：实现清单

### 前端应用

- [ ] 微前端容器（qiankun）
- [ ] Admin应用
  - [ ] 路由配置
  - [ ] 商家管理页面 + CRUD
  - [ ] 账户管理页面 + CRUD
  - [ ] 通路管理页面 + CRUD
  - [ ] 系统监控页面
  - [ ] 仪表板
- [ ] User应用
  - [ ] 登入页面
  - [ ] JWT token管理
  - [ ] 仪表板
  - [ ] 商品管理页面 + CRUD
  - [ ] 通路配置页面
  - [ ] 赛场管理页面 + CRUD
  - [ ] 订单管理页面
  - [ ] 物流管理页面
  - [ ] 退货管理页面
- [ ] 共享库
  - [ ] 通用UI组件
  - [ ] API客户端
  - [ ] 工具函数
  - [ ] TypeScript类型

### 后端API

- [ ] Admin端点（商家、账户、通路CRUD）
- [ ] Admin监控端点（统计、日志）
- [ ] User登入端点
- [ ] User端点（产品、订单、赛场、物流、退货CRUD）
- [ ] 分页支持
- [ ] JWT认证中间件
- [ ] 错误处理和验证

### 测试

- [ ] Admin单元测试
- [ ] User单元测试
- [ ] API集成测试
- [ ] E2E测试

---

## 📚 参考资源

- qiankun官方文档：https://qiankun.umijs.org/
- Vue3官方：https://vuejs.org/
- Element Plus：https://element-plus.org/
- Pinia文档：https://pinia.vuejs.org/

---

**文档版本**：1.0
**最后更新**：2026-02-21
**负责人**：架构设计团队
**状态**：✅ 设计完成，待实现
