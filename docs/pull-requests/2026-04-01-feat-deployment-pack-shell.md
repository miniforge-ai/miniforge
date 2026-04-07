# feat: deployment pack shell

## Layer

Infrastructure

## Depends on

- `feat/deployment-pack-safety-gates`

## Overview

Adds the shell adapter for Pulumi, kubectl, and kustomize execution with structured result handling.

## Motivation

Deployment phases need one place to invoke external CLIs with timeouts, structured errors, and parsed machine-readable
output.

## Changes in Detail

- Add `shell.clj` with timeout-aware command execution.
- Add typed wrappers for deployment CLI calls.

## Testing Plan

- Run `bb pre-commit`

## Deployment Plan

- Merge before the deployment phase interceptors.

## Related Issues/PRs

- Parent feature: deployment pack
- Follow-up stack branches: `feat/deployment-pack-provision`, `feat/deployment-pack-deploy`,
  `feat/deployment-pack-validate`

## Checklist

- [x] Scope limited to CLI adapter behavior
- [ ] `bb pre-commit` recorded
