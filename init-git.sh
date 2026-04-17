#!/usr/bin/env bash
# ─────────────────────────────────────────────────────────────────────────────
# init-git.sh — One-time setup to push this project to GitHub.
#
# Usage:
#   bash init-git.sh https://github.com/YOUR_ORG/swiftpay.git
# ─────────────────────────────────────────────────────────────────────────────
set -euo pipefail

REMOTE_URL="${1:-}"

if [ -z "${REMOTE_URL}" ]; then
  echo "Usage: bash init-git.sh <github-repo-url>"
  echo "  e.g. bash init-git.sh https://github.com/yourname/swiftpay.git"
  exit 1
fi

echo "=== Initializing Git for SwiftPay ==="

# Only init if not already a repo
if [ ! -d ".git" ]; then
  git init
  echo ">>> Git repository initialized"
fi

# Stage everything (respecting .gitignore — .env will NOT be staged)
git add .

# Verify .env is NOT being tracked
if git ls-files --error-unmatch .env 2>/dev/null; then
  echo "ERROR: .env is staged! Aborting to protect secrets."
  git rm --cached .env
  exit 1
fi

echo ">>> Files staged (check below — .env should NOT appear):"
git status --short

git commit -m "feat: initial SwiftPay implementation

- Service A: Transaction Gateway (POST /v1/payments, Redis idempotency, Kafka producer)
- Service B: Ledger Service (Kafka consumer, atomic debit/credit, double-entry ledger)
- Docker Compose: Postgres + Redis + Kafka + both services
- GitHub Actions: build / test / docker push / EC2 deploy
- Load test: K6 script (250 TPS / 1M transactions)"

# Set remote
git remote remove origin 2>/dev/null || true
git remote add origin "${REMOTE_URL}"

# Rename branch to main if needed
git branch -M main

echo ">>> Pushing to ${REMOTE_URL} ..."
git push -u origin main

echo ""
echo "=== Done! ==="
echo "Now add these secrets to your GitHub repo:"
echo "  Settings → Secrets → Actions → New repository secret"
echo ""
echo "  EC2_HOST       = <your EC2 public IP>"
echo "  EC2_USER       = ubuntu   (or ec2-user)"
echo "  EC2_SSH_KEY    = <paste contents of your .pem file>"
echo "  REDIS_PASSWORD = swiftredis   (or your own password)"
echo "  DB_PASSWORD    = postgres     (or your own password)"
