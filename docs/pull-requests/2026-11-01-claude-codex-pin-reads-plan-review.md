<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# feat: The plan phase records whether the planner read its pinned landings

## Overview

Plan's `:pin-read?` (Codex SPEC §7.4.3) used to be nil on every run. It now
says true or false whenever the planner's session wrote a `context_read`
log (§7.4.2). A pin lookup that resolved `:source :absent` no longer counts
as a read. That bug was also live in implement, which shares the summary
function.

## Motivation

`pin-outcome` gives the planner a real pin file at `codex-pin/pin-path`,
the same way implement gets one, but `plan.clj` passed `nil` for the reads.
The data already existed: `with-session` and the submission-retry session
both return `:context-reads`, and the planner dropped it.

Copilot found the second problem. The MCP server records failed lookups as
`:source :absent`, and `consultation-summary` matched on path alone. An
agent that asked for its pin and got nothing still recorded
`:pin-read? true`, which suppressed the gate's `:codex-pin-not-fetched`
warning.

## Changes in Detail

- `agent/planner.clj`: concatenates `:context-reads` from the primary
  session and the submission-retry session, primary first, onto the
  planner result. The key is absent when neither session wrote a log.
- `phase-software-factory/plan.clj`: passes `(:context-reads invoked)`
  to `consultation-summary` instead of `nil`.
- `phase-software-factory/codex_pin.clj`: adds a Layer 0 `pin-fetch?`, true
  for a pin-path read that did not resolve `:absent`. `consultation-summary`
  uses it.
- `phase-software-factory/review.clj`: comment only. Review keeps `nil`
  because its landings are a prompt section (`landings-outcome`), not a pin
  file. With no file there is nothing to `context_read`, so passing the
  reads would make `:pin-read?` false on every review.

## Testing Plan

- `planner_test.clj`: retry reads append after the primary's. With no logs,
  the result has no `:context-reads` key.
- `codex_pin_test.clj`: a new `context-read` factory builds records in the
  producer's real shape, `{:path :source :timestamp}`. New cases: an
  `:absent` pin lookup gives false; `:cache`, `:filesystem` or `:refresh`
  after an `:absent` lookup gives true.
- The real `pin-fetch?` and `consultation-summary` forms were evaluated on
  Clojure 1.12 against six cases: nil log, other path, cache, absent,
  absent-then-refresh, and filesystem. All passed.
- CI runs the full suite. The agent and phase test namespaces need Clojars
  dependencies, which the authoring environment cannot reach.

## Deployment Plan

No configuration or data migration. Takes effect on the next run with
`MINIFORGE_CODEX_PATH` set. Plan consultations start recording
`:pin-read?` true or false, and the `:codex-consultation` gate's
informational `:codex-pin-not-fetched` warning can now fire for plan.

## Related Issues/PRs

- #1629: capsule sessions surface `:context-reads`.
- #2303: gate warnings persist to the phase result and gate history.

## Checklist

- [x] Adversarial review (dewey 720): traced the default path (codex
      unconfigured, no retry), the retry path, and nil versus empty logs.
      Confirmed the gap report counts `:pin-read?` only for the
      `:unheeded` (delivered) bucket.
- [x] Rules review: 001, 002, 006, 008, 009, 050, 210, 400, 600, 716, 721
- [x] Tests ship with the change (716)
- [ ] CI green on the current head
- [ ] Copilot findings resolved on the current head
