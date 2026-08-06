<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# docs: N4 policy packs spec completion (0.6.0 → 0.7.0-draft)

## Overview

Takes N4 (Policy Packs & Gates Standard) from a partially-specified document to
a complete one. Unifies the severity vocabulary the spec contradicted itself on,
adds the sections a gate needs to actually run — resolution, binding, execution
semantics — and fixes the schema drift left behind by the 0.6 four-artifact
rewrite.

Adds Annex A, an informative record of where the implementation diverges.

## Motivation

N4 governs what blocks a workflow. Its gaps are the kind that fail open.

**Two incompatible severity vocabularies.** §2.3.1 defined rule severity as
`:error` / `:warning` / `:info`. §3.3 defined violation severity as
`:critical` / `:high` / `:medium` / `:low` / `:info`. Every one of the eleven
standard packs in §5.1 used the second. §6.3's override rule tested
`severity ≤ :medium`, a value the rule vocabulary could not produce. A rule's
severity *is* what its violations carry, so this was not two views of one thing
— it was a lossy translation at exactly the boundary where enforcement is
decided. The canonical enum in `components/schema` has been the second one all
along; §2.3.1 was the outlier.

**A gate could not determine what to run.** N4 defined check functions, and N2
defined gates, but nothing defined how a gate acquires its rules. Multiple packs
binding to one gate had no precedence rule. Two packs disagreeing on a rule's
severity had no resolution. An unbound gate had no defined behaviour — and the
natural implementation of "no rules to run" is "pass".

**A policy pack is code, with no execution contract.** §3.1.1 said check
functions are pure and deterministic. Nothing said what happens when one throws,
hangs, or returns garbage — so the default is that a crashing check reports no
violations and the gate passes. §2.7.1 already assumes untrusted material
reaches the system and third parties author packs (§10), yet rules ran with the
workflow's full privileges under no timeout.

**Schema drift from the 0.6 rewrite.** 0.6 introduced the four-artifact model
and renumbered sections, but §11.1's complete example was never updated: it
still used the `:policy-pack/*` key namespace, a `:rule/name` field that no
schema defines, and omitted three fields §2.2/§2.3 mark REQUIRED. Anyone
copying it produced an invalid pack. §8.1 kept `:policy-pack/signature` against
§2.2's `:pack/signature`. Eight code citations still point at "N4 §2.4.2",
which now holds the mapping artifact.

## Changes in Detail

### Contract fixes

- **One severity vocabulary** (§2.3.1). Canonical `:critical :high :medium :low
  :info`, with per-severity enforcement stated. `:error` and `:warning` are
  withdrawn, with a normalization rule at the load boundary so packs authored
  against the old draft still load — on the same seam
  `schema/normalize-severity` already uses for `:major` / `:minor`.
- `:violation/rule-id` typed **keyword**, matching §2.3's "keywords, never
  strings". A string rule ID cannot be joined back to the pack that produced it.
- `:violation/pack-id` added — a rule ID alone does not identify which pack in
  the resolved set produced a finding once overlays and overrides are in play.
- `:failure/class` added to the violation schema for the execution-failure case
  (§3.5.1).
- §11.1's example rewritten to be schema-valid: `:pack/*` namespace,
  `:pack/taxonomy-ref`, `:rule/title`, `:rule/categories`, `:rule/auto-fix?`.
- §8.1 signature key aligned to `:pack/signature`; §5.1.7's result schema keys
  aligned to `:pack/id` / `:pack/version` and keyword rule IDs.
- `require-capability-declaration` was defined twice — §5.1.4 (a DAG task node
  lacking `:task/capabilities`, severity medium) and §5.1.9 (a Workflow Pack
  manifest not declaring capabilities, severity critical). §2.3 makes rule IDs
  globally unique, so this was a collision. Split into
  `require-task-capability-declaration` and
  `require-pack-capability-declaration`. No spec or code referenced either name.

### New normative sections

- **§2.1.1 Taxonomy compatibility.** `:taxonomy/min-version` meant nothing
  without defined bump semantics. Adding a category is MINOR; removing one or
  re-parenting is MAJOR; reusing a retired `:category/id` is forbidden. A pack
  referencing an absent category fails validation rather than loading with its
  classification silently dropped.
- **§3.5 Check function execution semantics.** Fail-closed: a throwing,
  timing-out, or malformed check is a rule *failure*, never a pass, and
  synthesizes a violation carrying `:failure/class`. Per-rule timeout and
  per-gate budget, with rules that did not run recorded distinctly. Isolation
  for packs below `:trusted` — which costs a conformant rule nothing, since
  §3.1.1 already requires purity. Determinism sampling.
- **§5.1 Standard pack registry.** Fourteen packs with canonical `:pack/id`
  values, obligation status, and owning spec. §5.1 previously introduced packs
  by bare string names (`foundations`) against §2.2's namespaced-keyword
  requirement; the identifier convention is now stated once and applied
  mechanically.
- **§5.3 Pack resolution and precedence.** The resolved rule set, computed
  before any check runs. On conflict: most-severe severity wins, `false`
  enabled wins, the owning pack's check function wins. Severity escalates
  across packs and never de-escalates — lowering is what overlays are for, and
  an overlay cannot weaken a pack it does not extend. Version conflicts fail
  rather than silently picking one.
- **§5.4 Gate binding.** How a gate acquires rules, as data. An unbound gate
  fails closed. A rule matching no artifact is `:skip`, not `:pass` — the
  distinction is what makes coverage auditable.
- **§5.5 Events and evidence.** Design principle 5 claimed observability;
  this names the obligations. `gate/started` plus exactly one terminal event
  per N3 §3.9; evidence records the binding, resolved set, exact versions
  (not ranges), pack hashes, and every waiver.
- **§6.3.1 Override and waiver.** §6.3 referenced an `allow-override?` defined
  nowhere in N4 — it is N2's `:gate/allow-override?`. Override now requires
  both that flag and severity ≤ `:medium`; `:critical` and `:high` need N8's
  multi-party approval, because bypassing them is an authorization decision.
  Every override produces a Waiver per N5-delta-supervisory-control-plane
  §3.1, and a waived gate MUST NOT report as passing anywhere.
- **§8.1.1 / §8.2.1 Signature canonicalization and trust roots.** §8 was
  unimplementable: it required verifying a signature without saying what bytes
  are signed. Now specified — and it matches what
  `policy-pack/crypto/pack-signable-bytes` already does. Plus: a present-but-
  invalid signature MUST NOT be downgraded to the unsigned path, and a pack
  MUST NOT supply its own verification key.
- **§9.4–§9.5 Conformance requirement IDs and test obligations.**
  `N4.PK.*`, `N4.EX.*`, `N4.RB.*`, `N4.EN.*`, `N4.TR.*`, plus nine named test
  obligations.

### Annex A (informative)

Standard 020 forbids extracting specs from code, so nothing here relaxes the
contract.

- **Type divergence** — `:pack/id` is `string?` in the implementation against
  §2.2's keyword. `:rule/id` is already correctly `keyword?`.
- **Specified, not implemented** — multi-pack resolution, gate binding,
  execution bounds, isolation, determinism sampling, and the waiver enforcement
  path.
- **Implemented, matching in shape** — `pack-signable-bytes` already dissocs
  the signature fields, sorts top-level keys, and serializes `pr-str` as UTF-8,
  which is the broad shape §8.1.1 specifies. Two rendering rules are missing:
  recursive map ordering and set normalization. Either can make the same pack
  serialize to different bytes across runs, so a signature valid on one machine
  fails on another — Annex A records it as a signature interoperability bug,
  not merely an unimplemented requirement.
- **Implemented, matching** — `normalize-severity` is the existing seam
  §2.3.1's legacy handling extends.
- **Structural** — eight code citations point at "N4 §2.4.2" (knowledge-safety,
  moved to §2.7.2 in 0.6); N2 §6.5 restates the violation schema with a string
  rule ID, which §3.3 now declares itself authoritative over.

### SPEC_INDEX

N4 entry updated; index 0.10.0 → 0.11.0-draft with an amendment-log entry.

## Testing Plan

Specification change; no runtime code touched.

- `markdownlint` clean on all three changed files.
- All Clojure example blocks verified brace-balanced after each edit.
- Every internal `§N.N` reference resolved against defined headings. The check
  covers intra-document references only; cross-spec references such as
  N1 §2.26 are outside its scope and were verified by hand.
- No duplicate section numbers; top-level sections ascending.
- Verified zero remaining `:rule/severity :error|:warning` and zero string
  `:violation/rule-id` values.
- Inbound `N4 §x.y` references enumerated before renaming anything; confirmed
  the five `N4 §5.1.9` citers reference the section, not the renamed rule, and
  that no spec or code referenced `require-capability-declaration`.

§9.5's test obligations describe tests that do not exist yet — that is the
follow-on work below.

## Deployment Plan

Documentation only. Merges to `main` with no runtime effect.

## Follow-on Work

Each is tracked by Annex A:

1. Migrate `:pack/id` to keyword in `policy-pack/schema.clj` (N4.PK.2).
2. Implement multi-pack resolution and conflict precedence (§5.3).
3. Introduce gate binding as data so N4.RB.5 has something to enforce (§5.4).
4. Add per-rule timeout and per-gate budget (§3.5.2).
5. Isolate untrusted pack execution (§3.5.3).
6. Wire the override path to produce a Waiver (§6.3.1).
7. Fix the eight stale "N4 §2.4.2" citations to §2.7.2.
8. Make N2 §6.5 reference N4 §3.3 rather than restate it.

## Related Issues/PRs

- Amends: N4 0.6.0-draft (#407, four-artifact taxonomy model)
- Follows: [#1641](https://github.com/miniforge-ai/miniforge/pull/1641) (N3 spec completion), same pattern
- Sources: N1 §2.24–§2.26, N1 §5.3.3, N2 §6.4, N3 §3.9,
  N5-delta-supervisory-control-plane §3.1, N7 §5, N8 §2.3/§3, N9 §8
- Governed by: `standards/miniforge/foundations/specification-standards` (020)

## Checklist

- [x] Spec reviewed against current state before editing
- [x] Internal contradictions identified and fixed
- [x] New sections use RFC 2119 keywords (020)
- [x] Annex A marked informative — carries no new requirements
- [x] No spec content extracted from implementation code (020 critical rule)
- [x] Copyright header present (810)
- [x] `markdownlint` clean
- [x] Code blocks brace-balanced
- [x] Internal section references resolve
- [x] Inbound references checked before renumbering or renaming
- [x] SPEC_INDEX updated (020: index is authoritative)
- [x] PR doc created (721)
