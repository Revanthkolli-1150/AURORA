#!/usr/bin/env bash
set -euo pipefail

echo "===================================================="
echo "⚡ AURORA Reliability Platform - Development Setup"
echo "===================================================="

# Copy environment template if .env does not exist
if [ ! -f .env ]; then
  echo "--> Creating .env from .env.example"
  cp .env.example .env
fi

# Install dependencies
echo "--> Installing monorepo workspace dependencies..."
npm install

# Build shared types
echo "--> Compiling canonical types..."
npm run build --workspace=@aurora/types

echo "--> Setup complete! Run 'npm run dev:console' to launch the UI."
