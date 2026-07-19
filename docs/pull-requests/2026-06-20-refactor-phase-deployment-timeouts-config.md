<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Externalize phase-deployment shell timeouts to EDN

## Overview

The `phase-deployment` component carried deployment-tool timeout values as bare
integer literals at each shell call site. This change moves those values into a
single EDN resource and loads them at namespace load time, leaving the original
literals in place as in-code fallbacks.

## Motivation

Deployment-tool timeouts (Pulumi, kubectl, kustomize, HTTP health checks, smoke
tests) are operational values an operator tunes per environment. Holding them as
data in a resource file, rather than scattered code literals, satisfies the
config-as-data rule (Dewey 007) and gives one place to read or adjust them.

## Changes

- Add `components/phase-deployment/resources/config/phase-deployment/timeouts.edn`:
  one non-namespaced map keyed per use-site/tool.
- In `shell/exec.clj`, `shell/pulumi.clj`, `shell/kubectl.clj`,
  `shell/kustomize.clj`, and `validate.clj`, add a private `timeouts` def that
  loads the EDN via `io/resource` + `edn/read-string`, and replace each timeout
  literal with `(get timeouts :key <literal>)`. The literal remains as the
  fallback when the resource is absent.

Key-to-literal mapping (values unchanged):

- `:exec-default-ms` 300000 — `sh-with-timeout` default
- `:pulumi-default-ms` 900000 — `pulumi!` default
- `:pulumi-outputs-ms` 30000 — `pulumi-outputs!` stack output
- `:kubectl-default-ms` 120000 — `kubectl!` default
- `:kustomize-build-ms` 60000 — `kustomize-build!` default
- `:kustomize-apply-ms` 120000 — kustomize apply via kubectl
- `:health-check-ms` 10000 — HTTP health check
- `:smoke-test-ms` 30000 — smoke test command

The `resources` path was already present on the component `deps.edn` `:paths`,
so no path wiring was needed.

`evidence.clj` content-hash `sha256:` strings are protocol values, not config,
and were left untouched.

## Verification

- Load smoke test: each loaded EDN value equals its original literal (all eight
  match).
- Isolated component tests: 47 tests, 120 assertions, 0 failures, 0 errors.
- `bb poly:check`: OK.
