# Systemd Service Fix for API Code Deployment

## Problem
After system restart, the API runs with old code instead of the latest rebuilt version. This happens because:
1. `start-on-boot.sh` was using `docker compose up -d` without `--build` (now fixed)
2. Systemd service `/etc/systemd/system/simpleec-oms.service` also doesn't rebuild images

## Solution

### Fix for systemd service
Update `/etc/systemd/system/simpleec-oms.service` to rebuild critical services on startup:

```bash
sudo nano /etc/systemd/system/simpleec-oms.service
```

Replace the ExecStartPre line with:
```ini
[Unit]
Description=SimpleEC OMS Docker Compose Services
After=docker.service
Requires=docker.service

[Service]
Type=simple
WorkingDirectory=/home/tom/ONEEC/simpleec-oms
ExecStartPre=bash -c "docker compose build simpleec-api simpleec-user-app simpleec-admin-app"
ExecStart=bash -c "docker compose up"
ExecStop=bash -c "docker compose down"
Restart=on-failure
RestartSec=10s
StandardOutput=journal
StandardError=journal

[Install]
WantedBy=multi-user.target
```

Then reload and restart:
```bash
sudo systemctl daemon-reload
sudo systemctl restart simpleec-oms
```

### Verify the fix
Check that services are rebuilt on restart:
```bash
docker logs simpleec-api | grep "HHH000412"  # Should show recent startup
```

## Changes Made (Feb 24, 2026)
- ✅ Fixed `start-on-boot.sh` to rebuild API, user-app, and admin-app on boot
- 📝 Created this guide for manual systemd service update
- Note: systemd service still needs manual update (requires sudo access)

## Root Cause
The issue stemmed from:
1. Controllers using incorrect @RequestMapping paths (missing /api prefix)
2. These were fixed in commits 0ed6853 and 7f594a7
3. But systemd service wasn't rebuilding images, so it ran old compiled code
4. Result: API endpoint routing errors 403/404 after restart despite fixes being committed
