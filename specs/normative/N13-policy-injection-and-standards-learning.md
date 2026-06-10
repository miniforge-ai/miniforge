<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N13 — Policy Injection & Standards Learning

**Version:** 0.1.0-draft
**Date:** 2026-06-09
**Status:** Draft
**Conformance:** MUST / SHOULD (staged — see §11)

_Splits policy into two tiers with different obligations: **enforcement**
(full-fidelity, gate-checked, completeness is the contract) and **guidance**
(a small, relevant subset injected into agent sessions so they pre-comply).
Establishes two learning flywheels over the guidance tier — per-repo
specialization and cross-repo promotion to a generic bootstrap set — with the
full-fidelity gate as the safety net that makes guidance pruning safe.
Motivated by the 2026-06-08 `phase-transition-graph-validator` dogfood
(`adhoc--670062198`), which failed 0/4: the standards addendum injected into
every agent phase was **153,111 characters (~38k tokens)** — the entire 50-rule
pack, because rule phase-scoping is nil on every rule — so Rule 050
(localization) was present but buried, the agent never pre-complied, the
reviewer blocked, and the repair loop burned out. Same root cause feeds the
N12 context-overflow problem._

Keywords MUST, MUST NOT, SHOULD, SHOULD NOT, MAY are used per RFC 2119.

Relationship to other specs: extends **N4 (Policy Packs / Policy Compilation
Contract)** — the pack and detectors are the substrate this layers on. Shares
the token-budget motivation with **N12 (Agent Context Economy)** — compact
guidance is a direct context-economy lever. The cross-repo flywheel is the
concrete instantiation of the "learned domain language" direction.

---

## 1. Purpose & Scope

A policy rule serves two distinct purposes that today are conflated into one
injected blob:

1. **It must be enforced** — the artifact MUST satisfy it before ship.
   Completeness matters: a rule not checked is a rule not enforced.
2. **It should be followed** — the agent producing the artifact SHOULD know
   it up front, so the artifact complies on the first pass instead of being
   rejected and repaired.

These have opposite size pressures. Enforcement wants _all_ rules (miss none).
Guidance wants _few_ rules (an agent cannot prioritize against a 38k-token wall;
signal drowns in volume). Conflating them — injecting the full pack as
guidance — fails both: it is simultaneously too big to guide and redundant
with the gate that already enforces.

This spec defines the two tiers, the selection of the guidance subset, the
learning loops that refine it, and the conformance staging. It does NOT change
the detector/enforcement semantics defined in N4; it changes only what is
**injected into agent prompts** and adds the **violation-learning** substrate.

---

## 2. The Two Tiers

### 2.1 Enforcement tier (full fidelity)

- The enforcement tier MUST evaluate the **complete** applicable rule set for
  the artifact, via deterministic detectors / policy gates (the
  `:policy-verify` / `:policy-review` gates in the implementation, under N4's
  gate contract), independent of what was injected into any agent.
- Enforcement MUST NOT depend on prompt injection. An agent that was never
  shown a rule is still bound by it at the gate.
- A `:policy-review` _agent_ (LLM judging rules without a deterministic
  detector) MAY be scoped to the rules touching the changeset, but MUST cover
  every such rule — it is part of enforcement, not guidance.

### 2.2 Guidance tier (compact, relevant)

- The guidance tier injects a **bounded subset** of rules into plan /
  implement / (and other authoring) agent sessions as prompt addendum.
- The guidance set for a (phase, repo, task) MUST be selected by §4 and MUST
  be bounded by a configurable token budget (§4.5).
- Guidance is advisory-for-prompt-assembly only. Dropping a rule from guidance
  MUST NOT drop it from enforcement (§2.1). This invariant is what makes
  guidance pruning safe (§9).

---

## 3. Rule Provenance

A rule may originate from three sources, in increasing specificity:

1. **Generic / bootstrap** — rules that have proven broadly valuable across
   many repositories (§6). This set seeds a fresh repository's guidance before
   any local signal exists (§7).
2. **Pack** — the full compiled standards/policy pack(s) for the repo (N4).
   The enforcement tier's universe.
3. **Per-repo learned** — the local specialization derived from this repo's
   violation history (§5).

The guidance set is a **selection over** these sources (§4); the enforcement
set is the **union** of all applicable pack rules.

---

## 4. Guidance Selection

For a given (phase, repo, task, changeset), the guidance set MUST be computed
by composing the following filters in order. Each stage narrows the candidate
set; cheap/static stages run first.

### 4.1 Static scope

Filter the pack by declared applicability: `:rule/applies-to`
(`:phases`, `:file-globs`, `:task-types`). A rule with empty applicability is
unscoped — per §4.6 a compilation warning, never a silent match-all. Only a
rule explicitly marked `:rule/always-inject?` is a guidance candidate across
all phases; an unscoped rule that is not `:rule/always-inject?` is excluded
from guidance until it declares scope (it remains fully enforced, §2.1).

> **Defect this fixes:** today every rule has nil `:rule/applies-to :phases`,
> and `rule-matches-phase?` treats nil as "matches all", so static scope is a
> no-op and the full pack is injected into every phase. Compilation MUST emit
> real applicability (§4.6); the runtime MUST NOT treat an unscoped pack as
> license to inject everything.

### 4.2 Learned rank (per-repo)

Rank the statically-scoped candidates by this repo's violation signal (§5):
rules that are violated-and-corrected frequently rank high; rules never
observed to be violated in this repo rank low. A rule the local agents
reliably comply with is dead weight in the prompt.

### 4.3 Changeset relevance

Boost rules whose `:file-globs` / category match the files or symbols the task
touches. A localization rule is more relevant to a task editing code that
emits strings than to a pure refactor of a data namespace.

### 4.4 Bootstrap fallback

When the per-repo ledger is empty or below a confidence threshold (new repo,
cold start), §4.2 has no signal; selection MUST fall back to the generic
bootstrap set (§7) intersected with §4.1 static scope.

### 4.5 Budget cap

The composed, ranked set MUST be truncated to a configurable token budget
(`:guidance/max-tokens`, default SHOULD be a small fraction of the phase
window — on the order of single-digit thousands of tokens, not the ~38k of
the full-pack dump). Only the
rule's `:rule/agent-behavior` (the terse actionable directive) is injected;
the bulky `:rule/knowledge-content` MUST NOT be injected into the session path
(it belongs in retrieval / enforcement rationale, not every prompt).

### 4.6 Compilation obligation

The MDC→pack compiler (N4) MUST emit `:rule/applies-to` (phases, file-globs,
task-types) and a stable `:rule/id` for every rule, so §4.1 and §5 attribution
have data to work with. A rule with no declared scope is a compilation
warning, not a silent match-all.

---

## 5. Per-Repo Violation Ledger (local flywheel)

### 5.1 Signal

Every enforcement outcome that attributes a defect to a rule is a violation
event. Sources MUST include: policy-gate failures (detector → rule id) and
reviewer findings that cite a rule (`:review/cause`, blocking-issues). Each
event records `{:rule/id, :repo, :phase, :outcome (violated|corrected), :ts}`.

### 5.2 Attribution

A finding MUST be attributable to a `:rule/id` to count. Deterministic detector
failures attribute directly. LLM reviewer findings SHOULD carry the rule id
they cite; findings that cannot be attributed are logged but do not feed
ranking (they instead signal a _missing_ detector — see §10).

### 5.3 Persistence

The ledger MUST persist per-repo (alongside the knowledge store), survive
across runs, and MUST NOT be shared across repos with different conventions
(cross-repo sharing happens only via promotion, §6, never by raw ledger
merge).

### 5.4 Ranking

Guidance rank (§4.2) is a function of recent violation frequency and
correction rate. A rule whose violation rate **drops to zero and stays there**
after sustained injection MAY be demoted out of the active guidance set (the
agents have internalized it / it does not apply here) — safe because the gate
still catches a regression and re-promotes it (§9).

---

## 6. Cross-Repo Promotion (global flywheel)

### 6.1 Promotion

A rule SHOULD be promoted to the **generic** set when it is independently
violated-and-corrected across at least K distinct repositories with a low
false-positive rate. Promotion means: this rule earns a place in the bootstrap
seed for repos that have never seen it.

### 6.2 Demotion

A generic rule SHOULD be demoted when it is dormant or noisy (high
false-positive / frequently overridden) across most repositories. Demotion
removes it from the bootstrap seed; it remains available per-pack for repos
that still scope it in.

### 6.3 Invariant

Promotion and demotion move only what is **injected as guidance / seeded**.
They MUST NOT alter any repo's enforcement universe. The full pack remains the
enforcement contract regardless of generic-set membership.

---

## 7. Bootstrap Seed

### 7.1 Cold start

A repository with no local violation history MUST bootstrap its guidance set
from the generic set (§6), statically scoped (§4.1) to each phase.

### 7.2 v0 generic seed

Until the cross-repo flywheel (§6) has sufficient data to derive the generic
set automatically, the seed is the hand-curated list in Appendix A —
distilled by the author across several thousand PRs (since 2026-01) and
previously fed into sessions manually. This list is the existence proof for
this spec: the manual loop it formalizes already worked; §5–§6 automate it.

### 7.3 Convergence

As the cross-repo flywheel accrues data, the derived generic set SHOULD
converge toward and then extend Appendix A. Divergence (the data demotes a
hand-curated rule, or promotes one not on the list) is signal, not error, and
SHOULD be surfaced for review.

---

## 8. Gateable vs Principle Rules

Rules divide by checkability:

- **Gateable** — a deterministic detector exists or is feasible (e.g.
  "no raw emitted strings", "`(get m k default)` over `(or (:k m) default)`",
  "no `requiring-resolve`", "factory functions for repeated maps"). These
  produce clean violation signal (§5), feed both tiers, and the learning loop
  runs sharp on them.
- **Principle** — LLM-judged, no clean detector (e.g. "follow stratified
  design", "choose simplicity", "functions read as pipelines"). These belong
  in the guidance tier only; their violation signal is fuzzy (reviewer
  judgement) and feeds ranking weakly.

The compiler SHOULD mark each rule's checkability. Enforcement (§2.1) acts on
gateable rules via detectors and on principles only via the `:policy-review`
agent.

---

## 9. The Gate as Safety Net

The two tiers reinforce rather than parallel: **full-fidelity enforcement
de-risks compact guidance.**

- Pruning a rule from guidance (§4.5) or demoting it (§5.4, §6.2) can only
  cause the agent to _stop being reminded_ of it — never to _escape_ it,
  because the enforcement gate evaluates the complete set (§2.1).
- A pruned rule that regresses fails at the gate, emits a violation event
  (§5.1), and is re-promoted into guidance by ranking (§5.4).

This closed loop means guidance can be aggressively minimized toward the
token budget without risking a silent policy escape. The objective in §10 is
therefore safe to optimize.

---

## 10. Objective & Metrics

Guidance selection is an optimization with a feedback signal. The objective:
**minimize injected guidance tokens subject to gate-pass-rate holding.**

Implementations SHOULD emit, per phase and per repo over time:

- `guidance/injected-tokens` — should trend down from the 153k-char baseline.
- `gate/first-pass-success-rate` — should hold or rise as guidance shrinks.
- `review/redirect-churn` — repair cycles per task; should fall.
- `policy/per-rule-violation-rate` — per rule, per repo; the learning signal;
  should fall for injected rules over time.
- `policy/unattributed-findings` — reviewer findings citing no rule id; a
  rising count signals a **missing detector** (a rule worth compiling), not a
  ranking input (§5.2).

If guidance shrinks and gate-pass holds, the loop works. If gate-pass falls as
guidance shrinks, the cap (§4.5) is too aggressive or ranking (§5.4) is wrong —
both observable.

---

## 11. Conformance Staging

- **Stage 1 (MUST, immediate):** Fix §4.1/§4.6 so the full pack stops dumping
  into every phase. Bound the guidance addendum (§4.5): static scope + budget
  cap + agent-behavior-only (drop knowledge-content from the session path).
  Seed guidance from Appendix A where static scope is unavailable. This alone
  removes the ~38k-token-per-phase bloat and surfaces the rules that matter.
- **Stage 2 (SHOULD):** Per-repo violation ledger (§5) — attribution,
  persistence, ranking. Guidance rank becomes data-driven.
- **Stage 3 (SHOULD):** Cross-repo promotion/demotion (§6); derived generic
  set converging toward Appendix A (§7.3).
- **Stage 4 (MAY):** Changeset-relevance ranking (§4.3) and missing-detector
  synthesis from unattributed findings (§10).

---

## Appendix A — v0 Generic Bootstrap Set

Hand-curated by the author across several thousand PRs (since 2026-01) and fed
into sessions manually prior to this spec. The seed for §7.2. Items are
recorded verbatim; mapping each to a compiled `:rule/id` and marking
gateable/principle (§8) is a Stage-1 task.

### Principles

- Follow stratified design.
- Choose simplicity.
- Configuration is data, not code. If there is config, pull it to a `.edn` file.
- Always localize — starting with `en-US.edn`. No raw strings.
- No magic numbers; use well-named constants that demonstrate intent.
- DRY — do not duplicate functionality. Create a component for common logic and import the interface.
- Code changes without test changes are rare. Re-examine.
- NO LEGACY CODE without explicit direction.
- There is ONE canonical place to find things. Not 2, not 3, not 5. If you find yourself creating an alternative path to
  find some data, stop. Fix the real problem.

### Hygiene

- PR DAG over giant PRs.
- Test files adhere to the same standards as source.
- Schemas and validation at the boundaries (interface boundaries, external data); inside components no validation (not
  needed).

### DRY

- Factory functions over duplicate maps.
- `success?` / `failed?` predicates instead of reaching into data structures for status.
- `success` and `anomaly` constructor fns instead of hand-built maps.

### Idiomatic use

- Extract any anonymous fn, or `let`/`cond`-type form, longer than a single line.
- `(get m k default)` over `(or (:key m) default)`.
- Create only composable functions that compose _up_ into larger pipelines that are _still small_.
- Do not nest conditionals; avoid them via composition or data-structure design.
- Functions must read as pipelines.
- When constructing maps, construction should be key→value; move value calculations into a `let` above the map so
  construction reads simply and intent is clear.
- `requiring-resolve` is an anti-pattern. Use proper `require`s. Genuine use cases are unicorn-rare.
