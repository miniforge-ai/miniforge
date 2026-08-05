<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N3 event stream spec completion (0.8.0 → 0.9.0-draft)

## Overview

Takes N3 (Event Stream & Observability Contract) from a partially-specified
document to a complete one. Adds the four normative sections the spec was
missing, defines the event types other specs already require but N3 never
declared, and fixes the internal contradictions that had accumulated across
seven prior amendments.

Also adds Annex A, an informative record of where the implementation currently
diverges from the contract.

## Motivation

N3 is the spec everything else builds on — N1 §5.5.2 emits through it, N2-delta
checkpoints through it, N5-delta materializes through it, N8 streams through it.
Its gaps propagate.

Three classes of problem:

**Missing normative machinery.** `:event/version` was REQUIRED on every event
with no rule for when it changes or what a consumer does on mismatch. Retention
was one sentence ("suggest 24h"). Nothing said what happens when emission
itself fails, so a workflow could report success on a stream missing the
`:gate/failed` that should have stopped it. §12.1 claimed the stream as a SOC 2
and FedRAMP audit trail while the stream carried unredacted tool arguments and
LLM prompts. There was no enumeration of event types, so no reader could
determine the contract's actual surface, and no requirement IDs, so no
conformance test could cite what it tested.

**Events required elsewhere, undefined here.** N2 §5 requires
`workflow/cancelled`. N2-delta §9 requires six checkpoint and resume events.
§5.3.6 of N3 itself referenced `listener/overflow` without ever defining it.
The N5 deltas define twelve `:supervisory/*` snapshot types; N3 §3.19 listed
five.

**Internal contradictions.** Two different sections were both numbered §3.17.
§2.3 preceded §2.2. `:pr/id` was a uuid in §2.3 and §3.16 but a string in
§3.10. Two families carried a bare `:timestamp` duplicating the envelope's
`:event/timestamp`. `:event/sequence-number` was `long` in §2 and `int` in
§3.19. The §14 cross-references to N7, N8, and N9 were each off by one section.
§2.3 admitted a nil `:workflow/id` only for N9 PR events, but reliability,
repo-intelligence, and pack-lifecycle events have no workflow either.

## Changes in Detail

### New normative sections

- **§6 Event Type Registry** — flat enumeration of all 107 emittable
  `:event/type` values with scope and retention class, one class per row so the
  table is mechanically readable. §6.1 requires §3, §4.1, and §6 to agree and
  recommends enforcing it mechanically. §6.2 fixes naming rules
  (subject-namespaced, past-tense, dotted namespaces not truncatable for
  filtering).
- **§7 Schema Evolution & Compatibility** — what `:event/version` versions
  (per event type, not the envelope), a change-classification table, five
  consumer obligations, and the major-version procedure. The rule that matters
  most: a consumer MUST ignore unknown event types and unknown fields, MUST NOT
  silently process a higher major version, and MUST NOT lose sequence position
  when it skips an event.
- **§8 Sensitive Data & Redaction** — never-emitted values, redaction at
  construction rather than at delivery, `"[REDACTED]"` marker (present, not
  omitted, so redaction stays visible to audit), truncation rules, and a
  three-way field classification driving `include-payloads=false` and
  per-recipient suppression.
- **§9 Emission Failure Semantics** — fail-closed for `:durable` and `:audit`
  classes, fail-open with counting for the rest, sequence numbers allocated on
  successful record rather than on attempt, and an explicit statement that
  listener backpressure is not emission failure.

### New event types

- **§3.21 Workflow Control & Checkpoint** — `workflow/cancelled`,
  `workflow/checkpoint-written`, `workflow/checkpoint-write-failed`,
  `workflow/machine-snapshot-written`,
  `workflow/machine-snapshot-write-failed`, `workflow/resumed`,
  `workflow/spec-hash-mismatch`. Sourced from N2 §5 and N2-delta §9.
- **§3.15 `listener/overflow`** — previously referenced by §5.3.6, never
  defined. Carries the dropped sequence range so a listener can resume at a
  known-good position.
- **§3.19.1** — the supervisory family enumerated at twelve members with entity
  shapes referenced to their owning delta specs rather than duplicated, plus
  the sole-emitter column made normative.

### Contract fixes

- `:pr/id` unified as the PR Work Item UUID across §3.10 and §3.16;
  `:pr/number` (long) added for provider-assigned numbers.
- Bare `:timestamp` removed from §3.10 and §3.13 in favor of the envelope's
  `:event/timestamp`.
- `:event/sequence-number` unified on `long`.
- §2.3 generalized from a PR-only carve-out to a scope-key table covering
  workflow, PR Work Item, pack, repository, supervisory entity, and deployment
  scopes. `:deployment/id` and `:supervisory/entity-key` introduced as the two
  scopes that previously had no key — without one, sequencing and subscription
  are undefined for those families.
- `:supervisory/schema-version` made REQUIRED on the supervisory family; the
  startup-replay precedence rule of N5-delta-1 §3.5 invariant 3 cannot resolve
  a shape change without it.
- §4.3 retention expanded into four classes with minimums, prefix-only expiry,
  and the interaction with the §5.3.5 replay horizon.
- §5.1, §5.2, and §5.3.1 generalized from workflow-only to every scope in the
  §2.3 table: `subscribe-to-scope`, `get-events-for-scope`, and a single-scope
  stream endpoint `/api/streams/:scope-type/:scope-id`. Without these, pack,
  repository, supervisory-entity, and deployment scopes had no way to be
  observed or recovered after a reconnect. §5.1.1 adds the canonical encoding
  for composite scope ids (`[repo number]` → `miniforge-ai%2Fminiforge:1641`).
- §2.1.1 added: fixed envelope field types, so families stop redefining them.
- §10.4 conformance requirement IDs (`N3.EV.*`, `N3.EM.*`, `N3.ST.*`,
  `N3.API.*`, `N3.CP.*`, `N3.SD.*`, `N3.EF.*`) and §10.5 test obligations.

### Structural

- Duplicate §3.17 resolved: Data Foundry renumbered to §3.20 and moved after
  §3.19. Reliability keeps §3.17 — it has twelve inbound references across N1,
  the gap analysis, and four source files; Data Foundry has none.
- §2.2 and §2.3 restored to numeric order.
- §6–§9 inserted; former §6–§10 renumbered to §10–§14. No inbound reference
  targets those sections.
- §14 cross-references to N7, N8, N9 corrected; N1, N2-delta, N4, and the N5
  deltas added.
- Version history gained the missing 0.7.0 entry, which the header note
  referenced but the history omitted.

### Annex A (informative)

Records implementation divergence as tracked work rather than silent drift.
Standard 020 forbids extracting specs from code, so nothing here relaxes the
contract — every row is a conformance gap to close.

- **Name divergences** — `repo-index/quality-computed` vs implemented
  `quality-measured`; `repo-index/canary-failed` vs `coverage-changed` (a
  different event, not a rename — the N1 §2.27.10 canary contract is
  unimplemented); `pr/opened` vs `pr/created`; `chain.edge/*` vs `chain/*`
  (the implementation models steps, the spec models edges); duplicate tool
  vocabularies (`tool/invoked` alongside `agent/tool-call-started`).
- **Specified, not implemented** — `subagent/spawned`, `milestone/reached`,
  three of five task lifecycle events, and the N7, N9, Data Foundry, and pack
  families in full.
- **Implemented, not specified** — 21 event types emitted with no §3 schema,
  including `workflow/phase-heartbeat` and `agent/stream-stalled`, which carry
  the stall-detection contract.
- **Structural** — `components/pipeline-runner` cites "N3 §2.4 pipeline run
  statuses", a section that has never existed in any version of N3;
  `pr-lifecycle` runs a separate in-process bus whose events are not sequenced,
  retained, or replayable per §2.2 and §4.3.

### SPEC_INDEX

N3 entry updated to list the new sections and requirement-ID families. Index
version 0.8.0 → 0.9.0-draft with an amendment-log entry.

## Testing Plan

This is a specification change; no runtime code is touched.

Validation performed:

- `markdownlint` clean on all three changed Markdown files (N3, SPEC_INDEX, this PR doc).
- All 98 Clojure example blocks verified brace-balanced by script, before and
  after each structural edit. One unbalanced block introduced mid-edit (the
  §3.13 common-fields block lost its closing brace when the `:timestamp` line
  was removed) was caught by this check and fixed.
- Every `§N.N` reference in the document resolved against the set of headings
  actually defined. Three apparent misses were confirmed to be cross-spec
  references to N1.
- No duplicate section numbers; top-level sections in ascending order.
- Inbound `N3 §x.y` references across `specs/`, `components/`, `bases/`, and
  `docs/` enumerated before renumbering, to confirm the Data Foundry move and
  the §6–§9 insertion break nothing.

The §6.1 registry-agreement check and the §10.5 test obligations describe tests
that do not exist yet. They are the follow-on implementation work, listed below.

## Review Rounds

Eleven Copilot rounds; every finding was real and is fixed. Recorded here because
the pattern is informative — most findings were consequences of the §2.3 scope
generalization not being carried through the rest of the document.

1. `:pr/number` typed `int` in one supervisory example against `long`
   everywhere else; `:durable` retention defined against "the workflow record"
   though the class covers non-workflow scopes; Annex A.1 ambiguous about which
   registry lists `pr/created`.
2. §6 supervisory row was prose ("the twelve types enumerated in §3.19.1"),
   defeating the section's purpose; rows carrying two retention classes
   contradicted §4.3.1's "exactly one class per type"; SPEC_INDEX omitted the
   supervisory entity scope.
3. §2.1.1 defined Conditional fields as OPTIONAL unless a scope key while
   §3.10 requires `:pr/id` on Workflow-scoped events; registry entries omit the
   leading colon and read as strings; scope labels diverged from §2.3.
4. §4.3.1's Members column redefined class membership and contradicted §6;
   `workflow/completed` still admitted `:cancelled` while §3.21 forbids it;
   `:workflow/id` typed `uuid` while marked nilable.
5. §5.1/§5.2/§5.3 still workflow-only after §2.3 went multi-scope, leaving four
   scopes unobservable and unrecoverable; N3.ST.4 narrower than the §4.3.2 rule
   it cites.
6. `workflow/completed` carried a `:workflow/status` admitting `:failure`
   while failure has its own event; `workflow/cancelled` required
   `:action/id` in prose but never declared it; `:pr/repo` missing from all
   nine §3.10 examples.
7. `listener/*` fixed to `:workflow/id` though §5.3.1 now streams six scope
   types and N3.API.7 requires every stream to open with `listener/attached`.
8. Round 7's fix overloaded `:workflow/id` to mean "the scope key",
   contradicting §2.1.1. Replaced with an explicit `:scope/type` field;
   `annotation/created` split onto its own registry row.
9. Cross-scope delivery was incoherent with per-scope sequencing — it would
   interleave two counters and make resume ambiguous. Delivery is now
   strictly by scope. Supervisory family glob was `:supervisory/*-upserted`
   though two of twelve members are not `*-upserted`.
10. The §2 base envelope still said `:workflow/id` REQUIRED and sequencing
    per-workflow; `control-action/*` and `annotation/created` examples
    omitted the now-required `:scope/type`.
11. §3.19's intro attributed every emission to supervisory-state though
    §3.19.1 gives `automation-edge-upserted` a different sole emitter;
    composite-key notation `[:repo :number]` read as keywords; Annex A did
    not record that §5.3.1's endpoint is unimplemented.

The last five rounds trace to one decision — generalizing §2.3 from
PR-only to six scopes — and its consequences through §3.15, §5, and the
registry. Worth noting for the next amendment: a scope-model change is not
local to the section that defines it.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

Not in this PR — each is an implementation change that Annex A now tracks:

1. Reconcile the Annex A.1 name divergences. Each needs a decision on which
   name is canonical, then a code change (the spec name wins unless a spec
   amendment says otherwise).
2. Amend N3 for the Annex A.3 event types that are legitimate capabilities
   built without a spec change — `workflow/phase-heartbeat` and
   `agent/stream-stalled` at minimum.
3. Implement the §6.1 mechanical registry-agreement check.
4. Fix the dangling `N3 §2.4` citation in `components/pipeline-runner`.
5. Implement the §10.5 conformance suite, particularly the forward-compatibility
   and fail-closed-emission cases.

## Related Issues/PRs

- Amends: N3 0.8.0-draft (#647, per-workflow streaming wire contract)
- Sources: N2 §5, N2-delta-phase-checkpoint-and-resume §9,
  N5-delta-supervisory-control-plane §3, N5-delta-2 §4.2, N5-delta-3 §4,
  N5-delta-4 §4.1, N1 §2.27.9–2.27.10, N1 §5.3.3, N1 §5.5.2
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Internal contradictions identified and fixed
- [x] New sections use RFC 2119 keywords (020: normative specs MUST)
- [x] Annex A marked informative — no RFC 2119 keywords carrying new requirements
- [x] No spec content extracted from implementation code (020 critical rule)
- [x] Event and evidence wire contracts centralized in N3, not duplicated into deltas (020)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] Code blocks brace-balanced
- [x] Internal section references resolve
- [x] Inbound references checked before renumbering
- [x] SPEC_INDEX updated (020: index is authoritative)
- [x] PR doc created (721)
