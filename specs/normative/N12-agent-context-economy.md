<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N12 — Agent Context Economy

**Version:** 0.1.0-draft
**Date:** 2026-06-07
**Status:** Draft
**Conformance:** MUST / SHOULD (staged — see §9)

_Establishes the agent context window as a bounded, governed resource:
how miniforge measures it, what it does as a prompt approaches the limit
(degrade before bail), and the long-horizon architecture for compressing
intent into a learned, referenceable symbol language. Motivated by the
2026-06-07 `review-redirect-convergence` dogfood (`adhoc-2135293220`),
where the planner's first turn silently overflowed a 200k window and the
run died at the plan phase before producing any work._

Keywords MUST, MUST NOT, SHOULD, SHOULD NOT, MAY are used per RFC 2119.

---

## 1. Purpose & Scope

The model context window is a hard, finite budget that bounds every agent
phase. miniforge **assembles** the context it hands to agents, so it both
owns the risk of overflow and holds the lever to manage it. This
specification defines:

- How an implementation MUST measure prompt size and detect overflow.
- How it MUST respond as a prompt approaches the window — **shed to a
  query surface before bailing**, never bail as the first move.
- The contract for a **symbolic query surface** (compact handles that
  unfold to larger context on demand).
- The forward architecture for a **learned domain language** mined from
  the usage corpus, so intent can be _referenced_ rather than _described_.

### 1.1 Foundational Principle — Compression Requires a Shared Codebook

A symbol compresses meaning only if the **decoder already holds the
codebook that expands it**. This single principle governs the whole spec:

- **Existing code** is post-formalization: its symbol system (the
  programming language, interface contracts, types) is already shared with
  the model via pretraining. Code is therefore compressible and
  unfoldable for free (§6).
- **Natural-language intent for concepts that do not yet exist** has no
  such shared symbol set. English is the maximally-shared codebook (the
  model is pretrained on it) and is therefore the **compression floor**
  for novel intent: verbose but universally decodable.
- A tighter codebook for intent does not exist a priori; it MUST be
  **grown** from usage and made available to the decoder at inference
  time (§7).

### 1.2 Relationship to Other Specs

- **N3 (Event Stream):** context measurements and degradation actions are
  emitted as events (§3, §5).
- **N6 (Evidence & Provenance):** learned symbols MUST be version-pinned
  with provenance (§7.4).
- **N4 (Policy Packs):** promotion of a mined concept to a canonical
  symbol is a policy-gated action (§7.3).
- **I-PHASE-HANDOFF-ENVELOPES (informative):** the typed phase-handoff
  artifact is the **reference implementation** of a schema-bound symbol
  language between two controlled endpoints (§8).

---

## 2. Definitions

- **Context window** — the model's maximum input-token capacity, sourced
  from the model catalog (`:capabilities :context-window`).
- **Total input tokens** — `input + cache-creation + cache-read` tokens
  for a turn. A large prompt lands mostly under cache-creation; the
  prompt-level `input_tokens` field alone is NOT a faithful measure.
- **Pre-flight estimate** — an input-size estimate computed from the
  assembled prompt **before** the request is sent.
- **Context overflow** — a turn whose total input tokens meet or exceed
  the context window. A terminal, non-recoverable condition.
- **Query surface** — a tool interface through which an agent retrieves
  context on demand (e.g. the `context` MCP server) instead of receiving
  it inlined.
- **Symbol / handle** — a compact reference that **unfolds** to a larger
  context via the query surface.
- **Unfold target** — the canonical artifact a symbol expands to
  (definition + examples + linked source).
- **Manifest** — a compact index of handles (path/name + summary +
  signature + size) substituted for inlined bodies.
- **Shared codebook** — the set of symbols whose expansion both the
  assembler and the decoding agent already agree on.
- **Learned domain language** — a corpus-mined, growing set of named
  handles for recurring domain concepts (§7).

---

## 3. Pre-flight Measurement (Conformance: MUST)

3.1 Before dispatching an agent request, an implementation MUST compute a
pre-flight size gauge over the **assembled** prompt — both the system
prompt and the user prompt — not the user prompt alone.

3.2 The gauge MUST include a token estimate. When derived by character
heuristic, the estimate MUST round **up** (ceil): as a headroom gauge,
truncation under-reports a near-boundary prompt and hides imminent
overflow.

3.3 The gauge MUST be emitted as an event (per N3) at dispatch time,
carrying at minimum system size, user size, total size, the estimated
input tokens, the backend, and the model. Because it is computed
pre-send, it MUST be captured even when the request is subsequently
rejected (where post-hoc `:usage` never arrives).

3.4 Implementations SHOULD record the actual post-hoc input-token totals
(from `:usage`, including cache fields) alongside completion tokens, so
realized headroom is observable per phase and the estimate can be
calibrated.

---

## 4. Overflow Detection (Conformance: MUST)

4.1 Overflow MUST be detected from **structured token counts** —
`total-input-tokens >= context-window` — and MUST NOT be detected by
matching the backend's human-readable error text (e.g. "prompt is too
long"). Such text is **localized product output** and is phrased
differently per backend; matching it is fragile on both the locale and
multi-backend axes.

4.2 When the model's context window is unknown (model absent from the
catalog), an implementation MUST NOT assert overflow from token counts;
it MAY fall back to other structured signals but MUST NOT regress to
localized-text matching.

4.3 A detected overflow MUST be classified as a **distinct terminal error
type** (e.g. `context_overflow`) that is **excluded from any
submission-retry / recovery set**. An overflowed turn produced no
artifact to recover, and a retry merely re-sends an over-budget prompt;
retrying it wastes budget and MUST NOT occur.

4.4 Overflow classification MUST use the **effective model actually
invoked** for the window lookup (the per-request model when one is
supplied), not a stale default, so detection is consistent with the call.

---

## 5. The Degradation Ladder (Conformance: MUST shed, SHOULD pre-empt)

When a prompt approaches the window, an implementation MUST NOT bail as
the first response. It MUST apply a degradation ladder, in order:

5.1 **Assemble** the context normally.

5.2 **Shed (MUST).** When the pre-flight gauge indicates the assembled
prompt is over (or within a configured margin, in estimated input tokens, of) the window, the
implementation MUST reduce eagerly-inlined context — preferentially the
largest, most reconstructible blocks (e.g. an explore phase's inlined
file bodies) — and rely on the **query surface** (§6) for that content
instead. The shed action MUST be emitted as an event (N3) identifying
what was shed.

5.3 **Re-measure & proceed.** After shedding, the implementation MUST
re-measure. If the prompt now fits, it MUST proceed with the reduced
prompt.

5.4 **Bail last (MUST).** Only if the **irreducible** prompt (system +
task intent + manifest) still exceeds the window MUST the implementation
terminate with the §4.3 terminal type. Bail is the last rung, never the
first.

5.5 **Pre-empt (SHOULD).** Where the pre-flight gauge already shows a
clearly-irreducible overflow, an implementation SHOULD refuse to dispatch
the doomed call rather than pay for a request that will be rejected. A
conservative margin SHOULD be used to avoid false-positives from the
coarse estimate; the §4 post-hoc detection remains the authoritative net.

5.6 Shedding MUST be loss-_recoverable_: any context removed from the
prompt MUST remain reachable by the agent through the query surface. An
implementation MUST NOT silently drop context the agent cannot re-fetch.

---

## 6. Query Surface & Symbol Handles (Conformance: SHOULD; MUST for shed targets)

6.1 An implementation SHOULD prefer giving agents a **query surface** over
inlining context. Context that an agent can fetch on demand SHOULD NOT be
eagerly inlined when doing so materially consumes the window.

6.2 Any context made available by handle MUST have a **deterministic
unfold target** — a canonical artifact the handle resolves to. An agent
MUST NOT be expected to expand a symbol whose unfold target is undefined
(doing so invites hallucinated expansion).

6.3 A **manifest** substituted for inlined bodies SHOULD carry, per entry,
enough to decide relevance without unfolding: identifier, a one-line
summary, a signature where applicable, and size. Signatures are preferred
over bare identifiers; bare identifiers over nothing — compression
quality is the richness of the manifest.

6.4 For existing code, the manifest's symbol layer SHOULD be derived from
the language's own structure (namespaces, public vars, interface contracts,
types), which is already shared with the model.

---

## 7. Learned Domain Language (Conformance: SHOULD / forward architecture)

The shared codebook for **intent** cannot be authored a priori. It MUST be
grown from the corpus of actual usage, so recurring concepts become
compact handles that future intent references rather than re-describes.

7.1 **Corpus.** Implementations SHOULD treat their structured run output —
specs (intent), plans (intent→structure), typed phase artifacts, the event
stream, evidence bundles, and outcomes (PRs/reviews) — as a self-labeling
`intent → realization` corpus suitable for concept mining.

7.2 **Mining & promotion.** Recurring concepts SHOULD be mined from the
corpus and, above a frequency/utility threshold, **promoted** to named
handles with canonical unfold targets (§6.2).

7.3 **Promotion gate (MUST when promotion is automated).** Promotion of a
mined concept into the canonical codebook MUST pass a governance gate
(policy- or human-reviewed). Auto-mined symbols are noisy; ungated
promotion pollutes the codebook and MUST NOT occur.

7.4 **Provenance & versioning (MUST).** A learned symbol's meaning drifts
over time. Each symbol MUST be pinned to its **definition-at-time** with
provenance (per N6), so a reference resolves to the meaning in force when
it was made.

7.5 **Decoder availability (MUST).** A learned symbol compresses only if
the decoding agent holds its definition at inference time. Implementations
MUST make canonical symbols resolvable to the agent (e.g. via a knowledge
query surface / retrieval). Amortizing the high-frequency tail into model
weights (periodic fine-tune) is the eventual optimization and is OPTIONAL.

7.6 **Seed.** The hand-authored standards/policy packs (named, coded rules
that unfold to reference content) and the phase-artifact schemas are the
cold-start seed of this codebook; the learned layer grows on top of them
rather than replacing them.

7.7 **Orthography is incidental.** Whether a handle renders as an ASCII
slug, a glyph, or any other token sequence is not normative. Compression
derives from the shared learned _definition_, not the surface form; a
novel orthography that is not in the decoder's codebook provides no
compression and MUST NOT be assumed to.

---

## 8. Boundary Compressibility Classification (Informative-normative)

The compressibility of a boundary is a function of how controlled and
schema-bound it is. Implementations SHOULD convert open boundaries into
schema-bound ones where feasible, and grow the learned codebook (§7) for
the boundaries that remain open.

| Boundary | Status | Mechanism |
|---|---|---|
| phase ↔ phase | **schema-bound today** | typed handoff artifacts; both endpoints controlled; schema = shared codebook (see I-PHASE-HANDOFF-ENVELOPES). This is the reference instance of §1.1. |
| code → agent | compressible | code is already symbolic; manifest / repo-map unfold surface (§6). |
| human intent → planner | open / English-bounded | one endpoint is open-world and not schema-bound; reducible only as the learned codebook grows (§7). |

---

## 9. Conformance Staging

- **§3 Pre-flight measurement, §4 Overflow detection** — MUST. These are
  achievable with existing substrate (model catalog, usage parsing) and
  are the baseline that makes overflow predictable rather than fatal.
- **§5 Degradation ladder** — `shed-before-bail` (5.1–5.4, 5.6) is MUST;
  pre-emptive refusal (5.5) is SHOULD.
- **§6 Query surface** — SHOULD overall; deterministic unfold targets for
  shed content (6.2) is MUST.
- **§7 Learned domain language** — forward architecture: SHOULD, with the
  promotion gate (7.3), provenance (7.4), and decoder availability (7.5)
  as MUST **once any automated promotion exists**.

---

## 10. Rationale (Non-normative)

The `adhoc-2135293220` dogfood failed because the planner's assembled
prompt — system prompt + spec + an explore phase that **eagerly inlined**
the in-scope file bodies — reached 204,282 input tokens against a 200,000
window. The overflow surfaced only as a post-hoc "Prompt is too long",
was mis-classified as a recoverable error, and triggered a doomed retry.
Every clause above is the generalization of one failure in that chain:
no pre-flight visibility (§3), text-based mis-detection (§4),
bail-without-shedding when a query surface for those exact files already
existed (§5, §6), and — looking forward — the absence of a referenceable
codebook that would let intent and code arrive compact in the first place
(§7).
