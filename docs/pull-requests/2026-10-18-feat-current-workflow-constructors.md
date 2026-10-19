<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Support current workflow start and phase constructors

## Overview and motivation

The public workflow-start and phase constructors still reserve legacy sequence
numbers before publication. Current journals must assign positions only when a
valid draft commits. Add current-profile construction without changing legacy
callers or claiming that the default host has migrated.

## Layer and dependencies

Event-stream foundation, based on main. Uses the existing workflow envelope
profile selector. The explicit agentless value is documented separately in #2046;
the constructor accepts the required keyword supplied by its caller.

## Changes in detail

- Support workflow intent on current workflow-start drafts.
- Support explicit expected-agent identity on current phase-start drafts.
- Use sequence-free envelopes for current phase-completion drafts.
- Preserve legacy constructor arities, payloads and sequence behavior.
- Keep missing required facts invalid; do not fabricate defaults.

## Testing plan

Verify legacy compatibility, current draft admission and durable publication,
including rejected incomplete drafts, then run lint, strata, composition, build
and package checks serially.

All three product event-stream suites passed serially (1m51s). The rebuilt CLI
passed 14 tests / 205 assertions in isolation, with only test sources overlaid.
Pinned standards scan: zero violations; scoped lint and inferred strata: zero
findings. The adversarial pass checked legacy reservations, explicit current facts
and refused drafts consuming no position. It also checked identity allocation
failures, closed-stream rejection, retry idempotence and reopened durable history.

## Deployment plan

No host switch. Follow-up workflow producers will supply actual intent, phase
identity, duration and artifact facts before fail-closed runner integration.

## Checklist

- [x] Adversarial standards and compatibility review
- [ ] Serial verification and signed bounded commits
- [ ] Exact-head review and all checks
