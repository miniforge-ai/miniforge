<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N5 Delta 2 — PR Readiness / Risk / Policy Scoring

- **Spec ID:** `N5-delta-pr-scoring-v1`
- **Version:** `0.1.0-draft`
- **Status:** Draft
- **Date:** 2026-04-20
- **Amends:** N5 — Interface Standard: CLI/TUI/API (§3.2.8 PR Fleet View, §3.2.9 PR Detail View)
- **Amends:** N5-delta-supervisory-control-plane-v1 (§3.1 PrFleetEntry, §3.4 supervisory-state component)
- **Related:** N3 (event stream), N4 (policy packs), N9 (external PR integration), `pr-train` readiness/risk
  algorithms

## 1. Purpose

N5 §3.2.8 (PR Fleet View) and §3.2.9 (PR Detail View) require a TUI that surfaces four derived columns per PR —
**Readiness**, **Risk**, **Policy**, and **Recommend** — with factor-level breakdowns available on drill-down.
Today those scores exist as live-computed functions in the Clojure libraries `pr-train` and `policy-pack`, but are
not materialized on the canonical `PrFleetEntry`. Consequently the Rust supervisory TUI has no way to render the
required columns without reimplementing Clojure algorithms.

This delta closes that gap by requiring the scores to be **pre-computed producer-side** and carried on the
supervisory entity, so all consumer UIs (Rust TUI, native console, web dashboard, FFI clients) render from the
same pre-derived values. It does not change N4 policy semantics or the `pr-train` algorithm surface.

### 1.1 What this delta adds

- Four new OPTIONAL nested fields on `PrFleetEntry` (§2): `:pr/readiness`, `:pr/risk`, `:pr/policy`,
  `:pr/recommendation`
- A producer-side scoring component contract (§3) that invokes the existing `pr-train` and `policy-pack`
  functions and feeds results back into the supervisory projection
- A new fine-grained event `:pr/scored` (§4.1) consumed exclusively by `supervisory-state`
- Consumer rendering expectations for the four N5 §3.2.8 / §3.2.9 surfaces (§5)

### 1.2 What this delta does NOT change

- N5-delta-1 §3.4 design invariant 6 — `supervisory-state` remains the sole emitter of `:supervisory/pr-upserted`.
  Scoring does not introduce a second snapshot event; scored entities are delivered via the existing upsert path
- N4 policy-pack evaluation semantics — this delta only specifies how the *result* is carried
- The `pr-train` / `policy-pack` function signatures — consumed as-is
- Consumer-side logic — Rust TUI MUST render pre-computed scores, never derive them

## 2. PR entity extensions

The following fields are added to `PrFleetEntry` (N5-delta-1 §3.1). All are OPTIONAL — a PR observed before
scoring has completed MUST render the "not yet scored" state described in §5.4 rather than placeholder zeros.

### 2.1 `:pr/readiness` (optional map)

Per-PR merge-readiness score produced by `pr-train.readiness/explain-readiness`.

```clojure
:pr/readiness
  {:readiness/score     double              ; [0.0, 1.0]
   :readiness/threshold double              ; config-sourced; ≥ it → :ready?
   :readiness/ready?    boolean
   :readiness/factors   [{:factor      keyword   ; :deps-merged | :ci-passed | :approved |
                                                 ;   :gates-passed | :behind-main
                          :weight      double
                          :score       double    ; [0.0, 1.0]
                          :contribution double   ; weight * score
                          :explanation string}]} ; human-readable, single line
```

Consumers MUST NOT assume the factor list is exhaustive; new `:factor` keywords MAY appear as the scoring
config evolves. Unknown keywords MUST render as a row with their literal keyword name.

### 2.2 `:pr/risk` (optional map)

Per-PR risk assessment produced by `pr-train.risk/assess-risk`.

```clojure
:pr/risk
  {:risk/score   double                     ; [0.0, 1.0]
   :risk/level   keyword                    ; :low | :medium | :high | :critical
   :risk/factors [{:factor      keyword     ; :change-size | :dependency-fanout |
                                            ;   :test-coverage-delta | :author-experience |
                                            ;   :review-staleness | :complexity-delta |
                                            ;   :critical-files
                   :weight      double
                   :value       any         ; factor-specific; MAY be a scalar or nested map
                   :score       double      ; [0.0, 1.0]
                   :explanation string}]}
```

`:risk/level` is the primary display primitive for the Risk column (§5.2); the TUI MAY choose a distinct colour
per level aligned with the existing `status_*` palette.

### 2.3 `:pr/policy` (optional map)

Aggregated external-PR policy result, produced by `policy-pack.external/evaluate-external-pr`. This is a
display-facing *summary* of the N4 PolicyEvaluation records; the authoritative evaluation records remain
unchanged and continue to be emitted via `:supervisory/policy-evaluated` (N5-delta-1 §3.1).

```clojure
:pr/policy
  {:policy/overall       keyword            ; :pass | :fail | :waived | :unknown
   :policy/packs-applied [string]           ; pack names
   :policy/summary       {:critical int
                          :major    int
                          :minor    int
                          :info     int
                          :total    int}
   :policy/violations    [{:rule-id  keyword
                           :severity keyword   ; :critical | :major | :minor | :info
                           :message  string
                           :path     string    ; file path, "" if global
                           :waived?  boolean}] ; true if a Waiver covers this violation
   :policy/artifacts-checked int}
```

`:policy/overall` MUST reflect waiver state: a violating evaluation entirely covered by Waivers yields
`:waived`, not `:fail`.

### 2.4 `:pr/recommendation` (optional keyword)

A single-keyword summary action combining readiness + risk + policy, intended for N5 §3.2.8 Recommend column.

```clojure
:pr/recommendation
  #{:merge       ; ready, low risk, policy passing
    :approve     ; ready, low-medium risk, policy passing, needs human approval
    :review      ; not yet ready; reviewer input wanted
    :remediate   ; policy failing with automated fix path
    :decompose   ; scope too large — split suggested
    :wait        ; blocked on dependencies / CI / upstream
    :escalate}   ; human escalation required
```

#### 2.4.1 Derivation rules (first match wins)

The scoring component MUST produce `:pr/recommendation` deterministically from the other three scored fields
using the rules below, evaluated top-to-bottom. The first matching row sets the recommendation.

| # | Condition                                                                                   | Recommendation |
|---|---------------------------------------------------------------------------------------------|----------------|
| 1 | `:policy/overall = :fail` AND any violation has `:severity ∈ {:critical :major}` (no waiver) | `:escalate`    |
| 2 | `:policy/overall = :fail` AND all violations have `:severity ∈ {:minor :info}` (no waiver)  | `:remediate`   |
| 3 | `:risk/level = :critical`                                                                    | `:escalate`    |
| 4 | `:readiness/ready? = false` AND any factor with `:factor ∈ {:ci-passed :deps-merged}` has `:score < 1.0` | `:wait` |
| 5 | `:readiness/ready? = false`                                                                  | `:review`      |
| 6 | `:risk/level = :high` AND a `:change-size` risk factor has `:score ≥ 0.8`                    | `:decompose`   |
| 7 | `:readiness/ready? = true` AND `:risk/level = :high` AND `:policy/overall ∈ {:pass :waived}` | `:escalate`    |
| 8 | `:readiness/ready? = true` AND `:risk/level = :medium` AND `:policy/overall ∈ {:pass :waived}` | `:approve`  |
| 9 | `:readiness/ready? = true` AND `:risk/level = :low` AND `:policy/overall ∈ {:pass :waived}` | `:merge`       |
| — | fallback (should be unreachable given the above cover the space)                             | `:review`      |

Notes on the rules:

- **Rules 1–2** encode policy as the hardest gate: critical/major violations escalate to a human, minor-only
  violations suggest automated remediation. This mirrors how `policy-pack.external/evaluate-external-pr`
  already groups severities.
- **Rule 3** shortcuts critical risk regardless of readiness — operators want to see these even when CI is
  green.
- **Rules 4–5** split not-ready PRs: those blocked on CI or unmerged deps are a "wait it out" signal; those
  blocked on approvals or gates want human review.
- **Rule 6** uses a single threshold (`:change-size` factor score ≥ 0.8) rather than absolute line counts so
  that operator-tunable weights in `config/governance/risk.edn` continue to drive the outcome.
- **Rules 7–9** are the "happy path" tiers. `:policy/overall = :waived` is treated as passing because a Waiver
  record covers the violations per N5-delta-1 §6.

Implementations MAY layer finer-grained rules on top (e.g. a `:merge` → `:approve` override when the PR touches
flagged critical files) as long as the rules remain deterministic and documented. Consumer UIs MUST render the
emitted keyword verbatim; they MUST NOT recompute.

## 3. Scoring component contract

### 3.1 Placement

A dedicated component, `components/pr-scoring` (new), is introduced. It:

1. Subscribes to the event stream for fine-grained PR-mutating events (`:pr/created`, `:pr/ready-for-review`,
   `:pr/ci-status-changed`, `:gate/passed`, `:gate/failed`, `:pr-monitor/*`, and equivalents).
2. On each relevant event, resolves the current PR + train context (via the existing `pr-train` and `pr-sync`
   interfaces) and invokes:
   - `pr-train.readiness/explain-readiness`
   - `pr-train.risk/assess-risk`
   - `policy-pack.external/evaluate-external-pr`
3. Computes `:pr/recommendation` from the three results per the deterministic rules above.
4. Emits a single `:pr/scored` event (§4.1) carrying the PR identity plus the four scored fields.

### 3.2 Separation from supervisory-state

Per N5-delta-1 §3.4 invariant 6, `supervisory-state` remains the sole emitter of `:supervisory/pr-upserted`.
The scoring component MUST NOT emit `:supervisory/*-upserted` events. Instead, `supervisory-state` consumes
`:pr/scored` (§4.2) the same way it consumes other fine-grained PR events and merges the scored fields into
its in-memory `PrFleetEntry` before coalescing the next `:supervisory/pr-upserted` emission.

This preserves the one-source-per-entity invariant and the bounded-emission guarantee (§3.4.4).

### 3.3 Latency and freshness

Scoring is best-effort asynchronous. Consumers MUST render a `PrFleetEntry` without score fields as
"not yet scored" (§5.4); they MUST NOT block rendering waiting for scores. When scores update, the next
`:supervisory/pr-upserted` emission carries the fresh values; stale-score handling is therefore bounded by the
existing 100 ms coalescing window.

### 3.4 Idempotency

`:pr/scored` events MUST be idempotent: re-scoring the same PR with unchanged inputs MUST produce the same
scores bit-for-bit (within floating-point determinism of the input data). This allows `supervisory-state` to
suppress redundant upsert emissions per §3.4.4.

## 4. Event-stream additions

### 4.1 `:pr/scored` (fine-grained, producer = pr-scoring component)

```clojure
{:event/type      :pr/scored
 :event/id        #uuid
 :event/timestamp #inst
 :event/source    "pr-scoring"
 :pr/repo         string
 :pr/number       long
 :pr/readiness    {...}      ; §2.1
 :pr/risk         {...}      ; §2.2
 :pr/policy       {...}      ; §2.3
 :pr/recommendation keyword} ; §2.4
```

This event is consumed only by `supervisory-state`; other components MUST NOT rely on it directly. If a second
consumer is needed (e.g., a web dashboard bypassing supervisory state), a separate snapshot event SHOULD be
introduced rather than broadening this one.

### 4.2 `:supervisory/pr-upserted` — extension

The event schema in N5-delta-1 §3.4 is unchanged in shape. The carried entity MAY now include the four new
nested fields of §2. Consumers built against the pre-delta schema continue to work — unknown keys pass through
per the open-schema rule of N5-delta-1 §3.

## 5. TUI rendering — normative surfaces

### 5.1 Readiness column (N5 §3.2.8)

Display primitive: the `:readiness/ready?` boolean as a glyph (`✓` / `○` / `—`), optionally suffixed by the
score rendered to 2 decimal places. Colour MUST track the existing `status_ok` / `fg_dim` palette — `ready? =
true` uses `status_ok`; `false` uses `fg_dim`; absent scoring uses `fg_dim` + the "—" glyph.

### 5.2 Risk column (N5 §3.2.8)

Display primitive: `:risk/level` keyword rendered as a short tag (`low`, `med`, `high`, `crit`). Colour MUST
map `:critical → status_failed`, `:high → status_warning`, `:medium → status_warning`, `:low → status_ok`.

### 5.3 Policy / Recommend columns (N5 §3.2.8)

Policy: `:policy/overall` keyword as a short tag (`pass`, `fail`, `waived`, `?`). Recommend: the
`:pr/recommendation` keyword rendered verbatim (or a short alias table the renderer chooses).

### 5.4 "Not yet scored" state

When any of `:pr/readiness`, `:pr/risk`, `:pr/policy` is absent on the PR entity, the corresponding column MUST
render a neutral placeholder (e.g. `—`) in `fg_dim`, never a zero score or empty tag. This preserves the
ability to distinguish "no readiness" (score = 0.0) from "not yet scored" (field absent) — the former is an
actionable bad signal; the latter is in-flight backend work.

### 5.5 PR Detail View (N5 §3.2.9)

The detail view MUST render each factor list as a row-per-factor table with columns for factor name, weight,
score, and explanation. The detail view SHOULD also render the violation list from `:policy/violations` with
severity colouring mapped to the existing attention palette (`:critical → status_failed`, `:major →
status_warning`, `:minor → fg`, `:info → fg_dim`).

## 6. Rust consumer contract

The Rust `supervisory-entities` crate (`contracts/crates/supervisory-entities`) MUST extend `PrFleetEntry` with
four `Option<_>`-wrapped fields corresponding to §2:

```rust
pub struct PrReadinessScore { score: f64, threshold: f64, ready: bool, factors: Vec<ReadinessFactor> }
pub struct PrRiskScore      { score: f64, level: RiskLevel, factors: Vec<RiskFactor> }
pub struct PrPolicySummary  { overall: PolicyOverall, packs_applied: Vec<String>,
                              summary: PolicyCounts, violations: Vec<PolicyViolationSummary>,
                              artifacts_checked: u64 }

pub struct PrFleetEntry {
    // … existing fields …
    pub readiness:      Option<PrReadinessScore>,
    pub risk:           Option<PrRiskScore>,
    pub policy:         Option<PrPolicySummary>,
    pub recommendation: Option<String>,  // keyword carried as string
}
```

Rust consumers MUST NOT reimplement any `pr-train` or `policy-pack` logic — absent scores MUST render as §5.4
placeholders and the code surface MUST contain no dependency on readiness/risk heuristics.

## 7. Backwards compatibility and rollout

1. **Schema additions are purely additive.** Pre-delta consumers (including shipped Rust builds against the
   N5-delta-1 `PrFleetEntry`) continue to deserialize successfully — the new nested fields are ignored under
   the open-schema rule.
2. **Scoring component is optional in v1.** If `pr-scoring` is not deployed, the event stream carries no
   `:pr/scored` events, `supervisory-state` never populates the four fields, and consumers render the §5.4
   placeholder. This provides a clean "before" state for staged rollout.
3. **No migration of historical data.** Historical PR entities do not receive scores retroactively; scoring
   applies to events arriving after deployment. The operator surfaces SHOULD tolerate mixed fleets (some PRs
   scored, others not) without visual churn.

## 8. Acceptance criteria

An implementation satisfies this delta when:

- [ ] `components/pr-scoring` exists, subscribes to the listed fine-grained events, and emits `:pr/scored`
      events with all four §2 fields on every scored PR
- [ ] `supervisory-state` consumes `:pr/scored` and carries the scored fields on the next
      `:supervisory/pr-upserted` emission
- [ ] The Clojure `PrFleetEntry` schema and Rust `PrFleetEntry` struct both contain the four new fields as
      OPTIONAL
- [ ] The Rust TUI renders Readiness / Risk / Policy / Recommend columns per §5.1–§5.4 and a factor-breakdown
      table per §5.5
- [ ] Absent-score and mixed-fleet scenarios render the §5.4 placeholder without errors
- [ ] The `:pr/recommendation` derivation rules are documented alongside `pr-scoring` config so score inputs
      fully determine outputs
