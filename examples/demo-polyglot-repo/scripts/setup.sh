#!/bin/bash
# Setup script — demo file with intentional violations

# VIOLATION: hardcoded secret
export DATABASE_PASSWORD="super-secret-password-12345"

# VIOLATION: hardcoded API token
API_TOKEN="ghp_1234567890abcdefghijklmnopqrstuvwxyz"

echo "Setting up portfolio service..."
curl -H "Authorization: Bearer $API_TOKEN" https://api.example.com/setup
