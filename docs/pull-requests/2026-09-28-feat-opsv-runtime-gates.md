<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Evaluate OPSV runtime governance gates

## Overview

Replace hard-coded failed gates with actual evaluation of all six registered N4
OPSV gates at actuation. Retain their diagnostics and a runtime DecisionEnvelope.

## Motivation

Governed execution needs an evaluated policy decision, not flags or fabricated
gate verdicts. Missing authority must still reduce execution to recommendation.

## Layer and dependencies

Application-layer phase integration; follows the governed coordinator PR #1932.
Provider and issuance wiring remain separate dependent changes.

## Changes in detail

- Read policy context, compiled revision and watermark only from runtime options.
- Evaluate every required gate and preserve both diagnostics and domain verdicts.
- Bind the decision to the supplied revision, actual gate IDs and watermark.
- Deny missing runtime policy and reject malformed policy contracts.
- Require a non-null, nonnegative watermark and create exactly one envelope.
- Use verified pipeline evidence, ignoring replacement evidence in policy context.
- Keep execution capabilities disabled until governed runtime wiring lands.

## Testing plan

All 19 phase tests / 179 assertions pass in both consuming projects.
Cover all-pass evaluation, independent gate failures, missing and malformed policy,
caller policy spoofing, evidence replacement and retained recommendation semantics.
The scoped standards scan reports zero findings across 28 files. Stratum and
Polylith pass, and the CLI builds. Commit hooks also check kondo and smoke tests.

## Deployment plan

No external effects are enabled. Trusted runtime options may provide
`:opsv/governance` with `:policy/context`, `:policy/revision` and
`:policy/event-watermark`. Input files cannot provide this authority-plane data.
The host must resolve the compiled policy revision; the phase does not load packs.

## Related issues/PRs

N7 governed actuation; PRs #1932 and #1933.

## Checklist

- [ ] Tests, standards, lint and build pass
- [ ] Signed commits and green CI
- [ ] Review findings fixed and resolved
