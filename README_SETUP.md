# SimpleEC OMS - Web & Database Setup

## Current Status ✓

The SimpleEC OMS system now has a complete working database and web interface for managing merchant data.

### What's Running

1. **Web API Server** (Port 8093)
   - Provides JSON REST API for accessing database
   - Endpoints:
     - `GET /api/stats` - Get count statistics
     - `GET /api/merchant` - Get merchant details
     - `GET /api/accounts` - List all accounts
     - `GET /api/products` - List all products
     - `GET /api/orders` - List all orders

2. **HTTP Server** (Port 8000)
   - Serves HTML web pages
   - Main page: `http://localhost:8000/data-manager.html`
   - Files location: `/home/tom/ONEEC/simpleec-oms/web/`

### Database

- **Host**: localhost
- **Port**: 5433
- **Database**: simpleec
- **User**: simpleec
- **Password**: simpleec123
- **Merchant**: a00000 (測試商家)
- **Accounts**: 3 test accounts (ACC0001-ACC0003)
- **Products**: 3 test products (PROD001-PROD003)
- **Orders**: (empty)

## Starting Services

### Option 1: Using Startup Script (Recommended)
```bash
bash /home/tom/ONEEC/simpleec-oms/start-services.sh
```

### Option 2: Manual Start
```bash
# Terminal 1 - Start API Server
cd /home/tom/ONEEC/simpleec-oms
python3 simple-web-api.py 8093

# Terminal 2 - Start HTTP Server
cd /home/tom/ONEEC/simpleec-oms/web
python3 -m http.server 8000
```

## Accessing the System

### Web UI - Data Manager
- URL: `http://localhost:8000/data-manager.html`
- Features:
  - View merchant information
  - List all accounts with details
  - View products with pricing and inventory
  - Display orders (if any exist)
  - Statistics dashboard

### API Direct Access
- Base URL: `http://localhost:8093/api/`
- Example: `curl http://localhost:8093/api/stats`

## Database Management

### Using the CRUD CLI Tool

The `db-crud.sh` tool provides a simple command-line interface for database operations:

```bash
# View merchant information
bash /home/tom/db-crud.sh merchant

# List accounts
bash /home/tom/db-crud.sh accounts

# List products
bash /home/tom/db-crud.sh products

# List orders (with optional status filter)
bash /home/tom/db-crud.sh orders pending
bash /home/tom/db-crud.sh orders shipped

# View statistics
bash /home/tom/db-crud.sh stats

# Add new account
bash /home/tom/db-crud.sh add-account ACC0004 "新員工" "new@a00000.com" "password123"

# Add new product
bash /home/tom/db-crud.sh add-product PROD004 SKU004 "滑鼠墊" 50 199 100

# Update product inventory
bash /home/tom/db-crud.sh update-product PROD001 45

# Update order status
bash /home/tom/db-crud.sh update-order-status ORD001 shipped
```

### Using Direct SQL (psql)

```bash
PGPASSWORD=simpleec123 psql -h localhost -p 5433 -U simpleec -d simpleec

# Inside psql:
\dt                              # List all tables
SELECT * FROM merchant;          # View merchants
SELECT * FROM account;           # View accounts
SELECT * FROM product;           # View products
SELECT * FROM orders;            # View orders
```

## File Structure

```
/home/tom/ONEEC/simpleec-oms/
├── simple-web-api.py              # REST API server
├── db-crud.sh                      # CLI database management tool
├── start-services.sh               # Service startup script
├── web/
│   └── data-manager.html           # Web UI for data management
└── README_SETUP.md                 # This file
```

## Test Data

### Merchant
- ID: a00000
- Name: 測試商家 (Test Merchant)
- Email: merchant@example.com
- Phone: 0912345678
- City: 台北
- Status: active

### Accounts
1. ACC0001 - 管理員 (Admin) - access_level: 999
2. ACC0002 - 編輯員 (Editor) - access_level: 0
3. ACC0003 - 檢視員 (Viewer) - access_level: 0

### Products
1. PROD001 - Test Product A
2. PROD002 - Test Product B
3. PROD003 - Test Product C

## Troubleshooting

### Port Already in Use
If port 8093 or 8000 is already in use:
```bash
# Kill process on port 8093
lsof -i :8093 | grep LISTEN | awk '{print $2}' | xargs kill -9

# Kill process on port 8000
lsof -i :8000 | grep LISTEN | awk '{print $2}' | xargs kill -9
```

### Database Connection Issues
```bash
# Test psql connection directly
PGPASSWORD=simpleec123 psql -h localhost -p 5433 -U simpleec -d simpleec -c "SELECT version();"

# Verify database is running
docker ps | grep postgres
```

### API Returns Empty Results
Check that the API server is running and connected to the database:
```bash
curl http://localhost:8093/api/stats
```

## Future Enhancements

The current setup provides basic CRUD and viewing functionality. Possible future features:

1. Create/Edit/Delete operations through the web UI
2. Order management and tracking
3. Return/Refund management
4. Multiple merchant support
5. User authentication and role-based access
6. Real-time inventory updates
7. Multi-channel integration (Shopee, MOMO, etc.)

---

**Last Updated**: 2026-02-21
**System**: SimpleEC OMS - Merchant Management Platform
