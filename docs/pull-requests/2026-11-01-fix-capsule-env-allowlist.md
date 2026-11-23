<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(dag-executor): default-deny env for untrusted capsules

## Overview

An untrusted task capsule now takes env-config variables by allowlist instead of a secret-name
denylist. The image's own ENV secrets and the runtime's injected proxies are blanked. Credentialed
git commands number their config after the container's own and run with git tracing off.

## Motivation

Review of #2104 found that the trust filter passed any env-config name its secret pattern missed
(AUTHORIZATION, PASSPHRASE). It also left an image's baked-in GH_TOKEN or credentialed
DATABASE_URL in place. Docker and Podman could inject the operator's proxies, with credentials,
into the capsule. And a credential's GIT_CONFIG_* entries started at index 0 over the container's
own.

## Changes in Detail

1. Default-deny env-config for untrusted capsules (`filter-env-by-trust`). It keeps:
   1. the shell basics and LC_*;
   2. CLAUDE_CODE_* knobs with no credential word;
   3. the agent backend's credentials;
   4. names in the new `:env-passthrough` key, which `task-executor.runner` forwards and the
      `acquire-environment!` protocol docstring documents.
2. Image ENV blanking (`untrusted-env-blanks`). `security-gate-check` reads the pinned image's ENV.
   An untrusted plan sets an empty value for each entry that has a secret's name
   (`redaction/secret-key?`, now on the redaction interface, or a credential word) or a secret's
   value, or that is git config. An untrusted launch whose image ENV cannot be read fails closed.
3. Proxies: an untrusted plan sets every proxy variable, empty unless env-config supplied one
   without credentials. `create-container` passes them with `-e`, which Docker's client-side
   proxy injection respects (docker/cli `runCreate` reads only `--env` for it).
4. Git credentials (`capsule-credential-env`, `container-git-count`, `combine-git-env`). The
   credential's entries are numbered after the container's own GIT_CONFIG_COUNT. Every exec env
   holding GIT_CONFIG_* entries gets `git-trace-off-env`.
5. Redaction: the connection-string pattern accepts an empty user name (`redis://:secret@host`).

## Testing Plan

1. Each new behavior has a test that fails on the previous head. The PR description lists them.
   `redaction/secret-key?`, newly public, has its own interface test.
2. dag-executor, release-executor, redaction, task-executor and llm-interface tests pass under the
   shared JVM lock.
3. The Docker lifecycle and env-var integration tests list their variables in `:env-passthrough`.
   CI's Docker run exercises acquire against a real daemon, image ENV inspect included.

## Deployment Plan

Ships with the capsule credential stack. Behavior change: a task that needs an env-config
variable in an untrusted capsule must list it in `:env-passthrough`.

## Related Issues/PRs

1. #2104 (review threads), #2139, #2143, #2166, #2239 (stack below).
2. #2306: the `oci_cli.clj` split, which carries this stack's later `oci_cli.clj` review fixes.

## Checklist

- [x] Tests for each behavior change
- [x] Rules review and adversarial review (rule 720)
- [ ] CI green, Copilot comments resolved
