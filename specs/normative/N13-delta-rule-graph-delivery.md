<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# N13-delta — Rule Graph Query for Policy Delivery

**Version:** 0.1.0-draft
**Date:** 2026-10-19
**Status:** Draft
**Conformance:** MUST / SHOULD
**Amends:** N13 §4, §5.4, §6.2, §7.2, §9–§11
**Related:** N1, N3, N4, N12

---

## 0. Decision Record

> **Agents SHALL receive standards rules as the result of a deterministic query over a compiled rule graph. The
> query key is the phase, the task type and the changeset. The `.mdc` frontmatter flag `alwaysApply` SHALL NOT
> decide what miniforge injects, and SHALL NOT change how a rule is enforced.**

File globs stay. They are scope data, and the query matches them against the changeset.

This delta is a proposal and is not yet approved. Today miniforge picks injected rules from `alwaysApply`,
Dewey-derived phases and a hard-coded id list. The same `alwaysApply` flag also sets a rule's default enforcement
action and severity. A delivery edit can therefore change a gate. The rules already cite each other, and nothing
reads those references.

Source: the project owner's review of miniforge-standards#110 on 2026-10-18. That review asked why rule delivery is
context injection rather than a query for related compiled rulesets. The design builds on the N13 two-tier model and
the N12 §6 query surface.

---

## 1. Purpose and Scope

This delta defines:

1. The rule graph: what it holds and how it is built (§4).
2. The named queries a phase runs against it (§5).
3. What an agent receives, and how it fetches more (§6).
4. The invariants that keep delivery from weakening enforcement (§7).
5. How the IDE path and the standards format relate to the graph (§8).

§2 records the current delivery paths with measurements. §2 is informative.

### 1.1 Non-Goals

1. No change to which rules the policy gates select. D8 records a gate pack-set gap that N4 owns.
2. No change to how Claude Code or Cursor read `.mdc` files. That path stays frontmatter-driven.
3. No fix for the blocked standards re-pin. §7.7 adds the diff that makes a re-pin reviewable.
4. No dependency on thesium-workflows. Cross-cutting infrastructure stays in miniforge.
5. No free-form Datalog for agents. Agents call named queries with bounded results.

---

## 2. Current Delivery Paths (informative)

Measured on `main` at `a16f06778` on 2026-10-19 by calling the shipped functions in a REPL. Tokens are characters
divided by 4. The run loaded 62 rules: 59 from the standards pack and 3 built-ins. No user or repo packs existed.

### 2.1 Paths

1. **Guidance (plan, implement).** `phase/load-guidance-addendum` keeps the 16 ids in `bootstrap-seed-rule-order`.
   It filters by phase, keeps `:rule/agent-behavior` only, and caps output at 12,000 characters. It ignores its
   context argument, so file globs and the changeset play no part.
2. **Full addendum (review, release).** `phase/load-and-filter-behaviors` injects every `:rule/always-inject?` rule
   whose phase matches. Other rules pass through `policy-pack/filter-applicable-rules`. Callers pass no `:artifact`,
   so the matched path is the empty string, and every pinned file glob fails. The addendum includes
   `:rule/knowledge-content`, the full rule body.
3. **Task type.** Callers pass the spec intent, shaped `{:type ...}`, where the filter reads `:intent/type`. Task-type
   filtering therefore never fires. No compiled rule declares task types today.
4. **Knowledge store.** `knowledge-helpers/inject-with-manifest` selects Zettelkasten notes by role tags. Rule notes
   load with empty links. `knowledge/lookup-policy-rules` searches rule notes by text, tag and Dewey prefix, but no
   agent tool exposes it. This read found no CLI run path that sets `:knowledge-store`.
5. **Enforcement.** The `:policy-verify` and `:policy-review` gates check rules per changed file. They emit one
   `gate/rule-applied` event per rule. The LLM judge runs only for semantic rules whose action is `:hard-halt` or
   `:require-approval`. Other semantic rules get status `:guidance`, and no check runs. The gates load only the
   shipped standards pack, unless the context carries `:policy-packs`.
6. **Compiler.** `mdc->rule` maps `alwaysApply: true` to `:rule/always-inject? true`. The same flag sets the default
   action (`:warn`, else `:audit`) and severity (`:high`, else `:low`). An explicit `enforcement.action` overrides
   the action. Phases come from the Dewey range, not from frontmatter.
7. **IDE.** Claude Code and Cursor read `alwaysApply` and `globs` from the submodule's `.mdc` files directly.

### 2.2 Size per Phase

| Phase | Path | Rules | Characters | ≈ Tokens | Of which rule bodies |
|---|---|---|---|---|---|
| plan | guidance | 14 | 4,977 | 1,244 | 0 |
| implement | guidance | 16 | 5,573 | 1,393 | 0 |
| verify | none | 0 | 0 | 0 | 0 |
| review | full | 28 | 177,471 | 44,367 | 168,957 |
| release | full | 24 | 146,568 | 36,642 | 139,503 |

Passing one `.clj` path as `:artifact` grows the review addendum to 209,827 characters (≈ 52,456 tokens). The
difference is the glob-scoped rules that the empty path hides.

### 2.3 Enforcement Coverage

Of 59 standards rules, 10 have an acting check: 9 semantic `:hard-halt` and 1 content-scan `:hard-halt`. Three have
a deterministic content scan that does not block. The other 46 are semantic rules with `:warn` or `:audit`, which the
gate never checks.

31 rules are enforced nowhere: no gate checks them, and no reviewer receives them.

1. 28 glob-scoped standards rules that apply at review and are not always-inject. Examples: `:std/polylith`,
   `:std/python`, `:std/rust`. `:std/rule-format` is one of them: its empty phase set matches every phase.
2. `:std/standards`, whose phases exclude review and which is not in the seed.
3. The built-ins `:400-check-existing-work` and `:420-structural-validity`. They apply at implement only, and the
   gate does not load the built-in pack.

### 2.4 Defects

1. **D1.** The 31-rule hole in §2.3.
2. **D2.** Guidance ignores the changeset. `:std/clojure` reaches implement in a Rust-only change. `:std/rust` never
   reaches implement, even in a Rust change.
3. **D3.** Delivered text carries no rule ids. A reviewer cannot cite the rule it applied, so N13 §5.2 attribution
   has no input. An agent cannot ask for more detail by id.
4. **D4.** `alwaysApply` changes enforcement. Standards#110 moved rules 210 and 211 from `alwaysApply` to globs. At
   the next re-pin, `:std/clojure` drops from `:warn`/`:high` to `:audit`/`:low`, and both rules leave the reviewer
   addendum. Rule 211 keeps its gate through an explicit `hard-halt`. Rule 210's content scan covers one pattern.
   The rest of its body loses its only enforcement.
5. **D5.** Release receives 36.6k tokens, of which 34.9k are rule bodies. The release agent writes a branch, commits
   and a PR description. It does not edit code.
6. **D6.** The corpus already cross-references itself. At standards `main`, a body scan finds 97 references between
   rules, and 31 of 59 rules cite at least one other. `foundations/self-documenting-code` cites 002, 006, 008 and
   810, among others. Nothing reads these references.
7. **D7.** Pin lag. The submodule is pinned at `72f451d`, 60 commits behind standards `main` at `b899758`. Compiling
   both and diffing shows enforcement-field changes in 4 rules: `bb-over-shell`, `clojure`,
   `clojure-exception-handling` and `header-copyright`.
8. **D8.** No single rule resolution. Delivery concatenates packs without override-by-id, so a duplicate id yields
   two entries. The always-inject branch skips `:rule/enabled?`. Delivery honors `:disabled-pack-ids` and the gate
   does not. The standards pack id is a string, while the documented config uses keywords.

---

## 3. Terms

1. **Rule graph.** A derived, read-only index over the resolved packs and the per-repo ledger.
2. **Delivery set.** What one agent session receives for one phase, task and changeset.
3. **Enforcement class.** For a rule, phase and changeset: `:gated`, `:checked`, `:judged` or `:unenforced` (§7.2).
4. **Always-on set.** Rules delivered with behavior text in every authoring phase, declared as data (§7.8).
5. **Manifest entry.** Rule id, Dewey code, title and enforcement class, without behavior text.
6. **Unfold.** Fetching a rule's behavior or body by id (N12 §6.2).

The rule graph is a derived index of N1 §2.7 policy packs. A delivery set is the policy part of an N1 §2.28 context
pack: ids and behavior first, bodies only on demand.

---
