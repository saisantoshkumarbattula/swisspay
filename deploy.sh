#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# deploy.sh — Called by GitHub Actions on EC2 via SSH after a push to main.
# Also runnable manually:  bash deploy.sh
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REPO_DIR="${HOME}/swiftpay"
REPO_URL="https://github.com/${GITHUB_REPOSITORY:-YOUR_ORG/swiftpay}.git"

echo "=== SwiftPay Deploy — $(date) ==="

# 1. Clone repo if it doesn't exist, otherwise pull latest
if [ ! -d "${REPO_DIR}/.git" ]; then
  echo ">>> Cloning repository..."
  git clone "${REPO_URL}" "${REPO_DIR}"
else
  echo ">>> Pulling latest code..."
  cd "${REPO_DIR}"
  git fetch origin main
  git reset --hard origin/main
fi

cd "${REPO_DIR}"

# 2. Ensure .env exists (must be created manually once on EC2)
if [ ! -f ".env" ]; then
  echo "ERROR: .env not found. Copy .env.example to .env and fill in values."
  echo "  cp .env.example .env && nano .env"
  exit 1
fi

# 3. Pull/build and restart services
echo ">>> Stopping old containers..."
docker compose down --remove-orphans || true

echo ">>> Building and starting containers..."
docker compose up --build -d

# 4. Wait for health checks
echo ">>> Waiting for services to become healthy..."
for i in $(seq 1 30); do
  GW_STATUS=$(docker inspect --format='{{.State.Health.Status}}' swiftpay-gateway 2>/dev/null || echo "starting")
  LD_STATUS=$(docker inspect --format='{{.State.Health.Status}}' swiftpay-ledger  2>/dev/null || echo "starting")

  if [ "${GW_STATUS}" = "healthy" ] && [ "${LD_STATUS}" = "healthy" ]; then
    echo ">>> All services healthy!"
    break
  fi

  echo "    Attempt ${i}/30 — gateway=${GW_STATUS} ledger=${LD_STATUS}"
  sleep 10
done

# 5. Final health check
echo ""
echo "=== Health Check ==="
curl -sf http://localhost:8080/actuator/health | python3 -m json.tool || echo "Gateway not ready yet"
curl -sf http://localhost:8081/actuator/health | python3 -m json.tool || echo "Ledger not ready yet"

echo ""
echo "=== Deploy Complete ==="
echo "Gateway:  http://$(curl -sf http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo 'localhost'):8080/swagger-ui.html"
echo "Ledger:   http://$(curl -sf http://169.254.169.254/latest/meta-data/public-ipv4 2>/dev/null || echo 'localhost'):8081/swagger-ui.html"
