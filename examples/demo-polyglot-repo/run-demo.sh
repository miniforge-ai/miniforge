#!/bin/bash
# Miniforge Compliance Demo — polyglot repository
#
# Demonstrates three-tier analysis:
#   1. Policy packs (mechanical regex — secrets, map-access, TODO/FIXME)
#   2. Language linters (Clippy, ruff, clj-kondo, ESLint, SwiftLint)
#   3. Semantic analysis (LLM-as-judge — stratified design, code quality)
#
# Usage:
#   cd examples/demo-polyglot-repo
#   bash run-demo.sh

set -e

echo "═══════════════════════════════════════════════════════"
echo "  Miniforge Compliance Scanner — Polyglot Demo"
echo "═══════════════════════════════════════════════════════"
echo ""

echo "Step 1: Analyze repository..."
echo "─────────────────────────────"
bb miniforge init .
echo ""

echo "Step 2: Scan (policy packs + linters)..."
echo "─────────────────────────────────────────"
bb miniforge scan
echo ""

echo "Step 3: Scan with semantic analysis (LLM-as-judge)..."
echo "──────────────────────────────────────────────────────"
bb miniforge scan --semantic --report false
echo ""

echo "═══════════════════════════════════════════════════════"
echo "  Demo complete."
echo "═══════════════════════════════════════════════════════"
