<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Execute governed OPSV PRs from trusted runtime configuration

## Overview

Connect the phase's evaluated gates and DecisionEnvelope to real grant issuance,
durable effect transactions, and the GitHub provider. Recommendations remain the
default; input documents cannot configure the authority-plane runtime.

## Motivation

N7 PR emission needs an execution path that fails closed on replay, stale authority,
stop, policy mismatch, or uncertain provider outcomes.

## Layer and dependencies

Application integration follows PRs #1932, #1933 and #1934 in the
domain/coordinator/provider layers. The provider becomes a production dependency in both applications.

## Changes in detail

- Use one canonical effective-actuation input for recommendation and grant checks.
- Bind the runtime target to the verified policy hash and runtime workflow ID.
- Retain the policy hash in the durable, grant-bound governance receipt.
- Derive a stable effect ID per workflow/repository and refuse every stored replay.
- Issue exact PR-scoped authority and retain its identity independently of storage.
- Use the governed coordinator; only confirmed success produces PR references.
- Preserve uncertain/failed durable transactions in anomaly data for recovery.
- Add a monotonic in-process fence; stop prevents new admission, not rollback of
  an already admitted network operation. It cannot be reset by input data.
- Recheck the fence and current grant immediately before the POST, after GitHub's
  read-only head preflight. Observed stop revokes the issued grant.
- Revoke unused authority after proposal or registration failure.
- Recheck stop after an admitted POST without rewriting its confirmed or uncertain outcome.
- Retain cleanup failures alongside the actual provider observation.

## Testing plan

Tests use real temporary grant/effect stores and a simulated GitHub command port.
Coverage includes confirmed creation, draft-only failed verification, replay,
denied governance, recommendation/safe mode, expiry, stop before and during
preflight, and lost responses. No test opens an external PR. Provider tests cover
the new post-preflight dispatch boundary. Full project suites, hooks, scoped
standards scans and a packaged CLI build are required before merge.

## Deployment plan

Trusted `:execution/opts :opsv/pr-execution` configuration supplies separate
canonical `:effects-directory` and `:authority-directory`, a fresh `:clock`,
`:fence`, validated GitHub `:provider`, and a prepared `:target`. The target's
`:opsv/policy-hash` must match the verified pipeline output; the host owns branch
preparation and exclusive branch control. Runtime workflow status must be running.

This is not N7 completion: CLI host construction, global N8 stop/revocation,
multi-repository preparation, and complete failure/uncertainty N3/N6 publication
remain dependent slices. No live provider is enabled without host configuration.

## Related issues/PRs

N7 governed actuation; PRs #1932, #1933 and #1934.

## Checklist

- [ ] Tests, standards, lint and packaged build pass
- [ ] Signed commits and green CI
- [ ] Final-head review settled and comments resolved
