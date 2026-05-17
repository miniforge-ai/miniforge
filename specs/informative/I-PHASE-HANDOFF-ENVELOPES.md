<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# I-PHASE-HANDOFF-ENVELOPES — Informative

**Status:** Draft
**Date:** 2026-05-16
**Version:** 0.1.0-draft
**Anchor specs:** N2 §1.1, N2 §3, N2-delta, N3, N6

## Purpose

This note describes a typed envelope model for phase transitions. It informs
the N2 workflow transition and context-handoff contract without changing the
external wire protocol.

The motivating dogfood failure was a review-to-implement repair loop where the
review phase repeatedly identified missing acceptance groups, but later
implement attempts did not reliably treat that feedback as durable structured
input.

## Protocol Boundary

Transit remains the default external value transport for Rust-based clients
such as miniforge-control and fleet clients. The phase handoff envelope is the
domain protocol carried by that transport.

The intended layering is:

```text
phase transition data -> typed phase handoff envelope -> Transit codec -> clients
```

Future binary codecs can sit behind the same envelope boundary. They are an
optimization path rather than the first repair-loop fix.

## Envelope Shape

Phase transitions carry durable typed envelopes. A repair redirect can be
represented as:

```clojure
{:frame/version 1
 :frame/id #uuid "..."
 :workflow/id #uuid "..."
 :phase/id :review
 :phase/attempt 3
 :transition/from :review
 :transition/to :implement
 :frame/kind :repair-request
 :frame/schema :miniforge.phase-handoff.repair-request/v1
 :frame/refs [{:ref/kind :artifact-bundle
               :ref/id "cid:..."
               :ref/role :prior-implementation}]
 :frame/body
 {:repair/source-phase :review
  :repair/attempt 3
  :repair/findings
  [{:finding/kind :missing-acceptance-group
    :finding/group-id "GROUP 3"
    :finding/summary "CLI events show command missing"
    :finding/required-work [:cli-command :event-query :tests]}
   {:finding/kind :missing-acceptance-group
    :finding/group-id "GROUP 4"
    :finding/summary "Observer alert rules missing"
    :finding/required-work [:observer-rules :tests]}]}}
```

## Design Rules

- Phase output is transition data first; prompt text is a rendering of that
  data.
- Large payloads stay outside the frame and travel by reference.
- Feedback that drives a redirect is represented as typed findings, not only
  prose.
- The old payload can be preserved during migration so existing prompt paths and
  clients continue to work.
- Phase checkpoints and event replay can reconstruct the handoff without
  scraping stdout.

## First Slice

The initial implementation focuses only on review repair redirects:

1. Review produces a `:repair-request` handoff when redirecting to implement.
2. The handoff preserves reviewer findings and missing acceptance-group labels.
3. Workflow checkpoints retain the handoff through phase results and execution
   snapshot state.
4. Implement receives both the old `:task/review-feedback` and a new
   `:task/phase-handoff`.
5. The implementer prompt renders the handoff prominently before ordinary task
   details.

## Expansion Path

If the repair-loop slice proves useful, the same envelope model can expand to:

- verify failure handoffs
- plan deltas
- tool-call and tool-result handoffs
- code patch summaries
- release decisions
- observe outcomes
- cross-run learning records

The expansion should proceed phase by phase, using measured dogfood failures as
the acceptance criteria for each slice.
