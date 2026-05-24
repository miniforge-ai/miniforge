#!/usr/bin/env bash
set -e

# Miniforge Demo: Spec to PR
# ━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━
#
# Watch miniforge plan, implement, test, review, and release a feature —
# on its own codebase.

echo ""
echo "  ┌─────────────────────────────────────────────┐"
echo "  │         Miniforge Demo: Spec → PR           │"
echo "  │                                             │"
echo "  │  Watch miniforge add a utility function     │"
echo "  │  to its own codebase — autonomously.        │"
echo "  └─────────────────────────────────────────────┘"
echo ""

# Check prerequisites
MISSING=""

if ! command -v bb &>/dev/null; then
  MISSING="$MISSING\n  - Babashka (bb): brew install babashka/brew/babashka"
fi

# Check for the LLM backend wrapper.
HAS_BACKEND=false
if command -v opencode &>/dev/null; then
  HAS_BACKEND=true
  echo "  LLM backend: OpenCode CLI"
elif command -v claude &>/dev/null; then
  HAS_BACKEND=true
  echo "  LLM backend: Claude Code CLI"
elif command -v codex &>/dev/null; then
  HAS_BACKEND=true
  echo "  LLM backend: Codex CLI"
fi

if [ "$HAS_BACKEND" = false ]; then
  MISSING="$MISSING\n  - LLM backend: install OpenCode CLI and run opencode auth login"
fi

if [ -n "$MISSING" ]; then
  echo "  Missing prerequisites:$MISSING"
  echo ""
  exit 1
fi

echo ""
echo "  Starting in 3 seconds... (Ctrl-C to cancel)"
sleep 3

# Run the demo spec
bb miniforge run examples/demo/add-utility-function.edn
