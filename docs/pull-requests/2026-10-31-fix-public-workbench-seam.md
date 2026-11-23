<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(build): fetch the workbench Malli mirror from the public seam

## Overview

The two workbench adapters now fetch the Malli mirror of the workbench types
(`ai.miniforge.workbench-contract.schema`) from the public, Apache-2.0
`miniforge-ai/miniforge-app-foundation` repository over HTTPS. A clone with no
GitHub credentials resolves every dependency, so anyone can build `mf`.

## Motivation

The mirror came from the private `miniforge-ai/workbench-contract` repository
over SSH. Outsiders could not build `mf` from source, and `bb bootstrap` stopped
before the build to explain why. CI and release minted a GitHub App token to
fetch it. This blocked the first public release.

## Changes in Detail

1. `components/workbench-{etl,orchestration}-adapter/deps.edn`: the dependency is
   `io.github.miniforge-ai/miniforge-app-foundation` at an HTTPS URL, with
   `:deps/root "bindings/clojure"`. The namespace is unchanged, so no adapter code
   changes.
2. `.github/workflows/ci.yml` and `release.yml`: the four token and `insteadOf`
   steps are gone, with the `MINIFORGE_CI_BOT_APP_ID` env they read. After this
   PR no miniforge workflow reads the `MINIFORGE_CI_BOT_KEY` secret.
3. `tasks/bootstrap.clj`, `bb.edn`, `development/test/bootstrap_test.clj`: the
   private-dependency check and its tests are removed; there is nothing left to
   check.
4. `readme.md`, `docs/quickstart.md`: the note that only accounts with access
   could build from source is removed.

## Testing Plan

1. With `GIT_CONFIG_GLOBAL=/dev/null`, no SSH agent, and an empty `GITLIBS`, both
   adapters' classpaths resolve and the binding is cloned over HTTPS.
2. The ETL adapter, orchestration adapter and bootstrap tests pass: 32 tests,
   164 assertions.
3. The pinned binding commit runs the foundation's parity table, which checks
   the mirror against the Rust types.

## Deployment Plan

None. The pin is 792d2ae, the merge of miniforge-app-foundation#5 on that
repository's main.

## Related Issues/PRs

miniforge-ai/miniforge-app-foundation#5 publishes the binding.

## Checklist

- [x] Clean-credential dependency resolution checked
- [x] Pin is a commit on miniforge-app-foundation main
- [ ] CI green, Copilot comments resolved
