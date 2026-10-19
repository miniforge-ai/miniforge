<!--
  Title: Miniforge.ai
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# feat: Define explicit agentless phase event identity

## Overview and motivation

Native phases need an explicit expected-agent value before the workflow host can
publish current lifecycle events. The user requested a specific value rather than
nil. N3 reserves `:agent/none` for a phase intentionally running without an agent.

## Layer and dependencies

Foundation contract and acceptance tests, based on main. No dependency on the
workflow event decomposition. This does not switch producers or host composition.

## Changes in detail

- Keep expected-agent required and keyword-valued.
- Reserve `:agent/none` for intentional agentless execution, never unknown identity.
- Verify admission, context redaction, durable receipt and replay behavior.
- Retain rejection of missing/nil identities and conflicting occurrence retries.
- Correct the existing native OPSV finalization fixture to use the sentinel,
  not the invented agent identity `:opsv`.

## Testing plan

Run workflow draft and current-publication suites serially, then scoped lint,
inferred strata, Polylith composition and the pinned standards scan.

The two contract suites passed 12 tests / 181 assertions; the native finalization
acceptance suite passed 1 test / 60 assertions. Scoped lint and inferred strata
have zero findings. The pinned standards scan reports zero violations across
4,456 files. Adversarial review checked explicit absence versus malformed identity,
redaction preservation, refused retries, contiguous sequence and reopened history.
The isolated CLI package also passed all 13 tests / 241 assertions, using only
test overlays. Runtime code is unchanged; the existing build is reused.

## Deployment plan

No event-version change: the reserved value satisfies the existing keyword shape.
The follow-up producer migration must distinguish known native phases from missing
agent configuration. No agent implementation is registered under the sentinel.

## Checklist

- [x] Contract and adversarial standards review
- [ ] Serial verification and normal signed commit hooks
- [ ] Exact-head review, all checks and merge
