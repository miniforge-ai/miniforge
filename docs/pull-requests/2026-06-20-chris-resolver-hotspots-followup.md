<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# chore: clarify CLI optional provider registry

## Overview

This PR removes misleading resolver terminology from the CLI command optional
provider registry. The code path is an explicit composition registry, not a
dynamic resolver, so the public helper names now say what the boundary does.

## Motivation

The standards remediation work eliminated broad `requiring-resolve` and
`ns-resolve` anti-patterns. A follow-up scan still surfaced
`shared/try-resolve` and `shared/try-resolve-fn` in CLI command code even though
those helpers only read explicitly registered provider functions. Leaving the
old names makes future audits noisier and obscures the intentional composition
boundary.

## Changes in Detail

- Rename `shared/try-resolve` to `shared/optional-provider`.
- Rename `shared/try-resolve-fn` to `shared/call-optional-provider`.
- Update fleet and evidence CLI command call sites.
- Update CLI command tests and test descriptions to use provider terminology.

## Testing Plan

- Run focused CLI command tests for the renamed helpers and call sites.
- Run `bb pre-commit` before commit.
- Let pull request CI run the stable-derived test plan.

## Deployment Plan

No special deployment steps. This is an internal CLI helper rename with no
external command behavior change.

## Related Issues/PRs

- Follows the standards remediation wave that removed production resolver
  anti-patterns.
- Related to PRs #1230-#1235 and #1242.

## Checklist

- [x] Keep the change scoped to the CLI optional-provider registry.
- [x] Preserve explicit composition and fallback behavior.
- [x] Run local validation.
- [ ] Open PR and address review comments.
