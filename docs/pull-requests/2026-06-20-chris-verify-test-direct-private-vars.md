<!--\n  Title: Miniforge.ai\n  Author: Christopher Lester (christopher@miniforge.ai)\n  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.\n-->
# test: replace verify test dynamic resolves

## Overview

This PR removes a focused cluster of test-only dynamic `resolve` calls from the
phase-software-factory verify tests.

## Motivation

After production resolver remediation, the remaining resolver-looking patterns
are mostly tests that use `resolve`/`ns-resolve` for white-box access. The
verify test suite repeatedly resolves the same private `run-tests!` var at
runtime, which is noisier than an explicit test dependency on the private var.

## Changes in Detail

- Replace repeated dynamic `resolve` calls for
  `ai.miniforge.phase-software-factory.verify/run-tests!`.
- Keep the test scope limited to the verify suite.
- Preserve existing test behavior and white-box intent.

## Testing Plan

- Run the touched verify test namespaces.
- Run `bb pre-commit` before commit.
- Let pull request CI run the stable-derived test plan.

## Deployment Plan

No deployment steps. This is test-only cleanup.

## Related Issues/PRs

- Follows PR #1252, which classified remaining production function-local
  `require` boundaries.

## Checklist

- [x] Replace focused dynamic resolves.
- [x] Run local validation.
- [ ] Open PR and address review comments.
