<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: cookie-based auth gate for web dashboard

## Overview
Adds an optional login gate to the web dashboard so the server can be exposed
without leaving it fully open. Auth is disabled by default and activates only
when credentials are configured.

## Motivation
The dashboard was completely open with no auth surface. As fleet mode and remote
agents grow, we need at least a basic gate before the real direction (provider
SSO) ships.

> **Note:** This is explicitly a placeholder. The intended long-term direction
> is OAuth/OIDC with Anthropic/OpenAI as the identity provider so agent sessions
> can be projected onto the human-in-the-loop surface. See future SSO spec.

## Layer
Web / Server middleware

## Base Branch
`main`

## Depends On
None.

## Changes in Detail
- `components/web-dashboard/src/ai/miniforge/web_dashboard/server/auth.clj` (new):
  `build-auth-state`, `enabled?`, `public-request?`, `current-session`,
  `authenticate!`, `clear-session!`, `handle-login-page`, `handle-login-submit`,
  `handle-logout`, `unauthorized-response`. Constant-time password comparison.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/views/auth.clj` (new):
  Hiccup login page reusing existing dashboard CSS classes.
- `components/web-dashboard/test/ai/miniforge/web_dashboard/server/auth_test.clj` (new):
  Route-level tests: protected redirect, login flow (valid/invalid credentials),
  logout + post-logout block.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/server.clj`:
  added auth namespace require, login/logout routes, auth gate cond clause,
  `:auth` param in `start-server!`.
- `components/web-dashboard/src/ai/miniforge/web_dashboard/interface.clj`:
  updated docstring to document `:auth` option.

## Strata Affected
- `ai.miniforge.web-dashboard.server`
- `ai.miniforge.web-dashboard.server.auth` (new)
- `ai.miniforge.web-dashboard.views.auth` (new)

## Testing Plan
- [x] Auth disabled by default — existing dashboard tests unaffected
- [x] `auth_test.clj`: protected redirect, valid/invalid login, logout

## Deployment Plan
- Auth remains off unless `MINIFORGE_DASHBOARD_PASSWORD` env var or
  `:dashboard :auth :password` config key is set.
- No migration needed.

## Related Issues/PRs
- Future: provider SSO spec (Anthropic/OpenAI OAuth for agent projection)

## Risks and Notes
- Intentionally minimal — session cookie, no CSRF token. Acceptable for
  local/team use; not suitable for public internet exposure without the SSO layer.

## Checklist
- [x] Isolated onto a clean branch from `main`
- [x] Added PR doc under `docs/pull-requests/`
- [x] Auth disabled by default, no breaking change
