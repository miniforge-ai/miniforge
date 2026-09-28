<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Audit governed OPSV proposals and durable outcomes

## Overview

Require proposal publication and evidence correlation before PR provider I/O.
Record durable outcomes, including uncertainty, without converting a publication
failure into a claim that the external mutation failed.

## Motivation

N7 requires the decision and authority/effect references in N6 evidence.
Successful phase-output events alone omit failed and uncertain effect records.

## Layer and dependencies

Application orchestration in `phase-opsv` uses the public event, evidence, grant
and actuation interfaces. It follows the runtime execution and disposition contract PRs.

## Changes in detail

- Separate event delivery from phase projection and transaction orchestration.
- Share one governed-effect projection and one publication-failure data map.
- Require an active, matching workflow/evidence assembly before provider I/O.
- Revoke unused authority when proposal audit or evidence accumulation fails.
- Publish the durable proposal and final known disposition, correlated to the
  same grant, envelope, workflow and evidence bundle.
- Reload a durable outcome after an unconfirmed coordinator response, without retrying the provider.
- Preserve the actual transaction if later publication fails.
- Checkpoint accumulated authority/event joins even when phase completion fails.

## Testing plan

Use real temporary grant/effect stores and a simulated provider command port.
Audit regressions cover ordering, authority correlation, missing/finalized/mismatched
assemblies, failed accumulation, unknown outcomes and post-mutation publication failure.
Run both consuming projects, lifecycle integration, scoped standards, hooks and packaged CLI checks.

## Standards adversarial pass

Shared constructors remove repeated authority and failure maps. Decision logic,
event delivery, audit correlation and effect orchestration have distinct namespaces.
Each namespace stays within three dependency strata. Diagnostics use catalogs;
exceptions become anomalies, while interruption preserves the thread flag.

## Deployment plan

Emission requires a runtime event stream and active evidence assembly. No live
provider is enabled by this change. Full artifact persistence/finalization,
host construction, global emergency stop and recovery remain separate work.

## Related issues/PRs

N3, N6 and N7; follows #1935 and #1937.

## Checklist

- [ ] Both project suites, standards, hooks and packaged checks pass
- [ ] Final-head review settled, all checks green, conflict-free merge
