#!/bin/bash
# SimpleEC OMS Web Services Startup Script

echo "🚀 Starting SimpleEC OMS Web Services..."

# Start the Web API server on port 8093
cd /home/tom/ONEEC/simpleec-oms
python3 simple-web-api.py 8093 > /tmp/web-api.log 2>&1 &
API_PID=$!
echo "✓ Web API started on port 8093 (PID: $API_PID)"

# Start the HTTP server on port 8000 (serving the web pages)
cd /home/tom/ONEEC/simpleec-oms/web
python3 -m http.server 8000 > /tmp/http_server.log 2>&1 &
HTTP_PID=$!
echo "✓ HTTP Server started on port 8000 (PID: $HTTP_PID)"

# Save PIDs for later cleanup
echo $API_PID > /tmp/web-api.pid
echo $HTTP_PID > /tmp/http_server.pid

echo ""
echo "📊 Services Status:"
echo "  - Web API: http://localhost:8093/api/"
echo "  - Data Manager UI: http://localhost:8000/data-manager.html"
echo ""
echo "🔧 Management Commands:"
echo "  - bash /home/tom/db-crud.sh merchant      # View merchant info"
echo "  - bash /home/tom/db-crud.sh accounts      # List accounts"
echo "  - bash /home/tom/db-crud.sh products      # List products"
echo "  - bash /home/tom/db-crud.sh orders        # List orders"
echo "  - bash /home/tom/db-crud.sh stats         # View statistics"
echo ""
