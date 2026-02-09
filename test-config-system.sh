#!/usr/bin/env bash
# Test script to demonstrate the frictionless configuration system

set -e

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo "  Frictionless Configuration System Demo"
echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "1. List available backends with status"
echo "   Command: miniforge config backends"
echo
bb miniforge config backends
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "2. Try to set backend to codex (should fail without API key)"
echo "   Command: miniforge config backend codex"
echo
bb miniforge config backend codex || true
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "3. Set backend to echo (available backend)"
echo "   Command: miniforge config backend echo"
echo
bb miniforge config backend echo
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "4. View current configuration"
echo "   Command: miniforge config list"
echo
bb miniforge config list
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "5. Get specific config value"
echo "   Command: miniforge config get llm.backend"
echo
bb miniforge config get llm.backend
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "6. Set a config value"
echo "   Command: miniforge config set llm.max-tokens 16000"
echo
bb miniforge config set llm.max-tokens 16000
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "7. Verify the change"
echo "   Command: miniforge config get llm.max-tokens"
echo
bb miniforge config get llm.max-tokens
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "8. Validate configuration"
echo "   Command: miniforge config validate"
echo
bb miniforge config validate
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "9. Set backend back to claude"
echo "   Command: miniforge config backend claude"
echo
bb miniforge config backend claude
echo

echo "━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━━"
echo

echo "✅ All tests completed successfully!"
echo
echo "User config file location: ~/.miniforge/config.edn"
echo "View it with: cat ~/.miniforge/config.edn"
echo
