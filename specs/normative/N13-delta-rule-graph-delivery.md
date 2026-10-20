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

## 4. Rule Graph

### 4.1 Nodes

| Node | Key | Attributes |
|---|---|---|
| rule | `:rule/id` | title, Dewey code, behavior, body size and digest, detector, action, severity, enabled, pack, source path |
| category | taxonomy id | Dewey range, label |
| scope | glob, phase or task type | none |
| detector | detector kind | whether it acts |

Every attribute is a projection of pack or ledger data. The graph reads `:rule/applies-to` as the compiled map of
`:phases`, `:file-globs` and `:task-types`, not as the N4 §2.3 vector.

### 4.2 Edges

| Edge | From → To | Source |
|---|---|---|
| `:in-category` | rule → category | Dewey code |
| `:governs` | rule → glob scope | `:rule/applies-to :file-globs` |
| `:applies-in` | rule → phase scope | `:rule/applies-to :phases` |
| `:applies-to-task` | rule → task-type scope | `:rule/applies-to :task-types` |
| `:checked-by` | rule → detector | `:rule/detection` |
| `:related`, `:extends` | rule → rule | declared `related` key, else body scan |
| `:supersedes` | rule → rule | N4 `:rule/deprecated-by`, when present; no compiled rule carries it today |
| `:violated-in` | rule → repo | ledger (N13 §5): counts, last timestamp |
| `:co-violated` | rule → rule | derived from the ledger (Stage 3) |

Rule-to-rule edge types reuse the knowledge component's `LinkType` values `:related`, `:extends` and `:supersedes`.

### 4.3 Edge Provenance

Each rule-to-rule edge MUST carry `:edge/source`, one of `:declared`, `:inferred` or `:derived`. Declared edges come
from frontmatter, inferred edges from a body scan, and derived edges from the ledger. Ranking MAY weight sources
differently. An inferred edge MUST record a digest of the matched text, so a scan change shows in the graph diff.

### 4.4 Build and Load

1. The step that compiles a pack (`bb standards:pack`) MUST also compile that pack's graph from the same input.
2. At load, the runtime MUST merge the graphs of the resolved pack set (§4.6). A pack without a graph gets one built
   at load by the same function.
3. The build MUST be deterministic. Graph digests MUST be computed over canonical rule content and exclude
   timestamps such as `:pack/created-at`, so a recompile of unchanged rules keeps the digest.
4. Each graph MUST record the id, version and content digest of its pack, and the standards commit.
5. The runtime MUST check each graph's recorded digest against the loaded pack. On a mismatch, it MUST rebuild that
   graph from the loaded rules and emit a fact. The graph inherits trust from the signed pack (N4 §8) through this
   check and needs no signature of its own.
6. The graph MUST NOT store rule text. Rule text resolves from the pack by id.
7. The graph MUST NOT be edited by hand. Corrections go to the standards source or the compiler.
8. The graph SHOULD ship as a separate artifact beside its pack, `packs/<pack>.graph.edn`, so pack bytes stay
   unchanged.

### 4.5 Storage and Runtime

Queries MUST be pure functions over in-memory data in the `policy-pack` component. They MUST run under both
Babashka and the JVM, because the CLI project runs on Babashka without the Datalevin pod. The standards graph is
small: 59 rules and about 100 rule-to-rule edges. The ledger is per-repo runtime state under a gitignored path (N13
§5.3). A store built on the existing `datalevin` component MAY serve the cross-repo stage (N13 §6) in Fleet.

### 4.6 One Resolver

Delivery and classification MUST consume one resolved rule set from one `policy-pack` function. That function
applies the pack set, `:disabled-pack-ids` with one normalized id type, override-by-id (`resolve-rules`) and
`:rule/enabled?`. This fixes D8 for delivery. Whether the gates adopt the same pack set is for N4 (open question 7).

---

## 5. Queries

Every query takes and returns data. The same inputs, graph digest and ledger snapshot MUST give the same output.

### 5.1 `delivery-set`

```clojure
{:phase        keyword                          ; :plan :implement :review :release
 :task-type    keyword                          ; one defined field of the task intent
 :repo         string                           ; ledger key
 :changeset    [{:path string :action keyword}] ; empty at plan
 :scope-paths  [string]                         ; intent scope, files-in-scope, explore results
 :repo-paths   [string]                         ; tracked files, passed in by the caller
 :failed-rules [keyword]                        ; ids failed by the prior attempt's gate or review
 :budget       {:behavior-tokens int :manifest-tokens int}}
```

At review and release, `:changeset` MUST come from the artifact resolution the policy gates use. It MUST NOT come
from `:files-in-scope`, which describes intended scope rather than the change.

The query runs these steps in order:

1. **Candidates.** Keep resolved rules whose phase and task type match. Keep a file-scoped rule only when a glob
   matches a path. Paths are the changeset; when it is empty, the scope paths; when those are empty, the repo
   paths. Glob matching MUST use the gate's function.
2. **Classify.** Compute each candidate's enforcement class (§7.2).
3. **Rank.** Always-on rules come first, then failed rules, then rules one edge from a failed rule. Next comes ledger
   rank (N13 §5.4), once the ledger is confident. Ties break on the number of matching paths, then on citations from
   other candidates. Then come the bootstrap prior and rule id. The prior is the N13 Appendix A order, held as data.
4. **Fit.** Emit behavior text in rank order up to the behavior budget. Emit the remaining candidates as manifest
   entries up to the manifest budget. Report the rest as a count. The behavior budget defaults to N13 §4.5's
   `:guidance/max-tokens`.

Edges shape rank and serve the `related` query. The push block lists candidates only, so an edge never pulls an
out-of-scope rule into a prompt.

The output map has the keys `:always`, `:selected`, `:manifest`, `:overflow`, `:graph/digest` and `:packs`. Each
entry carries its id, Dewey code and class. It also carries the reason it matched: a glob, phase, failure or edge.

Budget rules:

1. Every candidate MUST appear in the output as behavior text, a manifest entry or part of the overflow count.
2. Always-on rules MUST NOT be cut. If they alone exceed the behavior budget, loading MUST fail with a configuration
   error.
3. Failed rules MUST NOT be cut. When they push past the budget, the block exceeds it, and the delivery fact records
   the excess.
4. At review, the fit step MUST NOT cut. §7.3 requires every applicable rule. Overflow uses the reviewer's
   multi-session split (§7.4).

### 5.2 Other Queries

| Query | Input | Output |
|---|---|---|
| `rule` | `:id`, `:detail` (`:behavior` or `:body`) | text at the pinned pack version; explicit not-found |
| `related` | `:id`, `:hops` (1–2), `:edge-types` | entries with edge type and source |
| `rules-for-paths` | `:paths`, `:phase` | manifest entries; for a new file type met mid-task |
| `search` | `:text`, `:dewey-prefix` | manifest entries; lexical over title, behavior and body |
| `enforcement-classes` | `:phase`, `:task-type`, `:changeset` | class for every applicable rule (§7) |

---

## 6. Delivery

### 6.1 Push Block

Example for implement on a Clojure change:

```text
## Policy rules (miniforge/standards@2026.08, graph 3f2a91)
Cite rule ids in findings. Fetch detail with rules_get(id).

Always on (8 rules; 1 shown):
- [std/localization 050, gated] <behavior>
For this changeset (3 files under components/**/src):
- [std/clojure-exception-handling 211, gated] <behavior>
- [std/clojure 210, checked] <behavior>
Also applicable (ids only):
- std/polylith-tool 312, judged: Polylith tool usage
+4 more: rules_for_paths(paths)
```

1. Every delivered rule MUST show its id. This fixes D3.
2. The block MUST name the pack versions and the graph digest. This is the N13.PI.1 provenance record.
3. The always-on part SHOULD be byte-stable across sessions, so it can sit in a cached system-prompt prefix.

### 6.2 Pull Tools

The MCP context server registers four tools in `tool-registry.edn`: `rules_get` (`rule`), `rules_related`
(`related`), `rules_for_paths` (`rules-for-paths`) and `rules_search` (`search`). Each result MUST be bounded in
size. The push block MUST be complete without the tools, since every candidate is listed or counted. An agent CLI
without MCP loses detail, not coverage. The tools supersede the `lookup_policy` plan in
`work/knowledge-mcp-query.spec.edn`. That plan's backing function, `knowledge/lookup-policy-rules`, exists, but its
MCP tool was never built.

### 6.3 Per Phase

| Phase | Tier | Delivery |
|---|---|---|
| plan | guidance | always-on behavior; ranked candidates over the scope paths |
| implement | guidance | always-on; changeset candidates; failed rules with behavior |
| verify | none | gates only; no agent addendum |
| review | enforcement | every rule applicable to the changeset, matched per file, with behavior; bodies per §7.4 |
| release | guidance | rules about the branch, commit or PR (Dewey 7xx with `:release`), behavior only |

Per-file matching at review closes the glob part of D1, as the gate already matches per file. The release narrowing
fixes D5. The releaser writes the branch, commits and PR text after review has run. Rules about those artifacts
(710, 715, 721, 722) therefore have no reviewer. They are `:unenforced` for release artifacts until a release-phase
check exists (open question 6).

### 6.4 Facts

This delta defines no payload. It needs three facts, whose payload contracts MUST be registered in N3 before Stage 1
code:

1. `policy/guidance-delivered`: delivered ids, each one's placement (behavior, manifest or overflow) and class.
2. `policy/rules-queried`: each pull-tool call, with the query and the returned ids.
3. `policy/rule-unenforced`: each applicable rule classed `:unenforced` (§7.3).

---

## 7. Composition with Enforcement

### 7.1 Separation

The rule selection of `:policy-verify` and `:policy-review` MUST NOT read the graph, the delivery set or the ledger.
Those gates evaluate the complete applicable set (N13 §2.1). Coverage checks run in a separate gate (§7.3).

### 7.2 Enforcement Classes

| Class | Meaning |
|---|---|
| `:gated` | A check with `:hard-halt` or `:require-approval` runs, by detector or semantic judge |
| `:checked` | A deterministic detector runs, and its findings do not block |
| `:judged` | No check runs; the gate reports the rule as `:guidance` in `gate/rule-applied` (N3) |
| `:unenforced` | A `:checked` or `:judged` rule that no reviewer receives for this changeset |

1. The gate's predicates for whether a check runs MUST move into the `policy-pack` interface. Today they are private
   to the gate. Gate and graph then call one definition.
2. Classification MUST run against the pack set the gates load. A rule from a pack the gates do not load is
   never `:gated`.
3. A rule is applicable when it applies to the changeset at any phase of the run. A `:checked` or `:judged` rule
   whose phases exclude `:review` has no reviewer, so it is `:unenforced`. `:std/standards` is an example.
4. A `:checked` rule's detector often covers one pattern of a longer rule. `:std/clojure` is an example. The rest of
   such a rule is enforced only through the reviewer.

The classes describe what the gate does today, including semantic rules gated by an LLM judge. N13 §8 describes
which rules should gate. This delta changes neither.

### 7.3 Pruning and Coverage

Authoring phases MAY show a rule as a manifest entry or count it in the overflow, without behavior text. That is safe
only because review and the gates still hold the rule.

1. Review delivery MUST include, with behavior text, every `:checked` and `:judged` rule applicable to the changeset.
2. Each applicable `:unenforced` rule MUST produce a `policy/rule-unenforced` fact. It MUST NOT be reported as passed.
3. A review without an LLM backend makes every applicable `:checked` and `:judged` rule `:unenforced`.
4. A separate `:policy-coverage` gate reads the classes and the facts. In Stage 1 it reports only.
5. From Stage 2, `:policy-coverage` MUST fail closed on an `:unenforced` rule unless a recorded waiver names it. The
   switch waits until a reference workload shows zero `:unenforced` rules.

### 7.4 Reviewer Inputs Are Enforcement Inputs

Moving reviewer rule bodies behind `rules_get` MUST wait for a calibration result from the `policy-calibration`
component. Judge recall with on-demand bodies MUST be non-inferior to recall with full bodies on a fixed review set.
Until then, the bodies of `:checked` and `:judged` rules stay in the reviewer prompt. Overflow keeps the existing
multi-session split in `review-session-systems`. That split MUST cut on rule boundaries, so each rule's id stays with
its text.

### 7.5 Correction to N13 §9

N13 §9 says a pruned rule can never escape enforcement. That holds for `:gated` rules only. For `:checked` and
`:judged` rules, the safety net is review delivery (§7.3). Ledger demotion (N13 §5.4, §6.2) MUST NOT remove such a
rule from review delivery.

### 7.6 Frontmatter Decoupling

The end state: the compiler takes enforcement action and severity only from explicit fields, and a change to
`alwaysApply` alone changes no gate outcome. The migration runs in two steps, so no rule weakens on the way.

1. Stage 1. A rule without explicit values keeps its `alwaysApply`-derived values, and the compiler warns for it.
   The §7.7 diff shows any change those values cause.
2. Stage 2. After standards declares `enforcement.action` and severity on every rule, and miniforge re-pins, the
   compiler MUST NOT derive either value from `alwaysApply`.

Under this rule, D4's downgrade of `:std/clojure` shows in the re-pin diff. The fix is an explicit
`enforcement.action` upstream before that re-pin.

### 7.7 Pack Diff

Any change to a compiled pack MUST attach a rule-by-rule diff, whether from a pin bump or a compiler change. The
diff covers action, severity, detector, applies-to, always-on membership and enforcement class. The October 2026 pin
analysis produced this diff by hand. It becomes a build output.

### 7.8 Always-On Set

The always-on set MUST be declared as data in miniforge, with a reason per entry. It MUST NOT be inferred from
`alwaysApply`. A repo MAY add entries in `.miniforge/config.edn`; removing one MUST record a reason there. The v0
proposal follows. Its behavior text totals 3,054 characters, about 764 tokens.

| Rule | Dewey | Reason |
|---|---|---|
| `:std/localization` | 050 | Required always-on; the 2026-06-08 dogfood failed 0/4 when it was buried |
| `:std/result-handling` | 003 | `:hard-halt` on any source change; a miss costs a repair cycle |
| `:std/validation-boundaries` | 004 | same |
| `:std/exceptions-as-data` | 005 | same |
| `:std/named-constants` | 006 | same |
| `:std/config-as-data` | 007 | same |
| `:std/no-dead-code` | 008 | same |
| `:std/self-documenting-code` | 009 | same |

---

## 8. IDE Path and Standards Format

The `.mdc` frontmatter stays the IDE contract. Miniforge reads `globs` as file scope, with the IDE's meaning.
Miniforge reads `alwaysApply` only for enforcement defaults during migration (§7.6) and for a drift note. The
compiler prints that note when the IDE's always-apply set and miniforge's always-on set differ.

A separate standards PR SHOULD add two things to the rule format. First, a `related:` list of rule paths with an
optional edge type. Second, an explicit `enforcement.action` and severity on every rule. Neither reaches miniforge
until the re-pin. Stage 1 works on the pinned content, using inferred edges.

Miniforge matches globs with `registry.support/glob-matches?`, which differs from IDE matchers at edges. `**/*`
matches the empty path, and a brace glob such as `**/*.{clj,cljc}` matches nothing. The graph and the gate MUST
share this one function. The compiler MUST reject a glob that this function cannot match as written.

---

## 9. Relationship to Existing Work

1. **N13 §4.1 and §4.3.** `delivery-set` covers static scope and changeset relevance. Changeset relevance moves from
   N13 Stage 4 (MAY) into this delta: review and release in Stage 1, authoring phases in Stage 2.
2. **N13 §7.2.** `bootstrap-seed-rule-order` moves from code to data. It becomes the rank prior, not the filter.
3. **N12 §6.** The pull tools are the query surface for policy content. The unfold target is the pack rule at the
   pinned version.
4. **Knowledge component.** Rule notes loaded by `load-rules-from-directory` are a second copy of rule text. The
   graph adds no third copy. See open question 3.
5. **thesium-workflows.** Its `kg-mcp-tools` lookup by id and full-text search map to `rules_get` and
   `rules_search`. Miniforge takes no dependency. `kg.datalog_query` has no counterpart (§1.1).

---

## 10. Conformance Staging

1. **Stage 0 (this delta).** Spec and index entry. Register the §6.4 facts in N3 before Stage 1 code.
2. **Stage 1 (MUST).** Build the resolver, the graph, `delivery-set` and `enforcement-classes`. Match per file at
   review and release, and show ids in every block. Declare always-on as data. Emit `:unenforced` facts through a
   reporting coverage gate. Add the §7.6 step 1 warnings and the §7.7 diff. Delivery is push only. Plan and implement filter
   candidates by the seed list plus the always-on set.
3. **Stage 2 (SHOULD).** Pull tools. Plan and implement switch to `delivery-set` with the seed as prior. Manifest
   and overflow output, release narrowing, fail-closed coverage (§7.3), and §7.6 step 2.
4. **Stage 3 (SHOULD).** Ledger rank (N13 Stage 2), `:co-violated` edges, and reviewer bodies on demand after §7.4
   calibration passes.
5. **Stage 4 (MAY).** A cross-repo graph store for N13 §6 promotion.

Stage 1 grows the review prompt for code changes (§2.2: about 52k tokens for one `.clj` file). The existing
multi-session review split absorbs that until Stage 3.

---

## 11. Metrics

These extend N13 §10:

1. `policy/rules-by-class`, per run and phase.
2. `policy/unenforced-count`, with a target of zero.
3. `guidance/injected-tokens`, per phase, split into always-on, selected and manifest.
4. `policy/query-count`, per rule id.
5. `review/attributed-findings-rate`, the share of reviewer findings that cite a rule id.

---

## 12. Open Questions

1. Membership of the v0 always-on set (§7.8).
2. The field that carries task type into `delivery-set` (§2.1 item 3).
3. Whether to retire knowledge-store rule notes or make them views over the graph.
4. The non-inferiority margin and review set for §7.4.
5. Plan-phase scope when the intent names no paths. All tracked files may be too broad.
6. A release-phase check for branch, commit and PR rules (§6.3).
7. Whether the gates should load the same pack set as delivery (D8, §4.6). That change belongs to N4.

---

## 13. Conformance Requirements

| ID | Level | Stage | Requirement |
|----|-------|-------|-------------|
| N13D.RG.1 | MUST | 1 | Build and load graphs deterministically, checked against the loaded pack's digest (§4.4). |
| N13D.RG.2 | MUST NOT | 1 | Store rule text in the graph, or edit the graph by hand (§4.4). |
| N13D.RG.3 | MUST | 1 | Resolve rules for delivery and classification with one `policy-pack` function (§4.6). |
| N13D.RG.4 | MUST | 1 | Select delivered rules with `delivery-set`, keyed by phase, task type and changeset (§5.1). |
| N13D.RG.5 | MUST | 1 | Account for every candidate as behavior text, a manifest entry or the overflow count (§5.1). |
| N13D.RG.6 | MUST | 1 | Show the id of every delivered rule, plus pack versions and the graph digest (§6.1). |
| N13D.RG.7 | MUST | 1 | Match review and release rules per changed file, with the gate's glob function (§5.1, §8). |
| N13D.RG.8 | MUST NOT | 1 | Let `:policy-verify` or `:policy-review` rule selection read graph, delivery set or ledger (§7.1). |
| N13D.RG.9 | MUST | 1 | Deliver every applicable `:checked` and `:judged` rule to review, with behavior text (§7.3). |
| N13D.RG.10 | MUST | 1 | Emit `policy/rule-unenforced` for each applicable `:unenforced` rule; never report it passed (§7.3). |
| N13D.RG.11 | MUST | 2 | Fail `:policy-coverage` closed on an unwaived `:unenforced` rule (§7.3). |
| N13D.RG.12 | MUST NOT | 3 | Move reviewer rule bodies on demand before calibration shows non-inferior recall (§7.4). |
| N13D.RG.13 | MUST NOT | 2 | Derive enforcement action or severity from `alwaysApply` (§7.6). |
| N13D.RG.14 | MUST | 1 | Attach a rule-by-rule diff to every change of a compiled pack (§7.7). |
| N13D.RG.15 | MUST | 1 | Declare the always-on set as data with a reason per entry, and never trim it to fit (§5.1, §7.8). |

### 13.1 Test Obligations

1. A one-file `.py` changeset puts `:std/python` in review delivery. A Clojure-only changeset does not.
2. With an empty always-on set, a one-token budget and no pull tools, every candidate appears as an entry or count.
3. With a non-empty always-on set and a one-token budget, loading fails with a configuration error.
4. A fixture rule flipped from `alwaysApply: true` to `globs` compiles to the same action and severity in Stage 1.
5. A review without an LLM backend emits `policy/rule-unenforced` for applicable `:checked` and `:judged` rules.
   It reports none of them as passed.
6. The same inputs, graph digest and ledger snapshot give byte-identical `delivery-set` output.
7. `:policy-verify` and `:policy-review` verdicts are identical with the graph present and absent.
8. A recompile of unchanged rules keeps every graph digest.

---

## Annex A — Measurement Method (informative)

The §2.2 sizes come from `agent-behavior/load-all-rules`, `load-guidance-addendum` and `load-and-filter-behaviors`.
Each phase was called with `{:task {:task/intent {:intent/type :implement}}}`. Production callers pass a different
shape (§2.1 item 3). No rule declares task types, so the sizes match either way. The §2.3 classes come from
`policy-pack/resolve-detector` and each rule's enforcement action.

The D6 count is a regex scan of `.mdc` bodies at standards `b899758`. It matches backticked `category/slug` paths and
three-digit Dewey references, and drops self-references. A parenthesized three-digit number that is not a rule
reference also matches, so 97 is an upper bound.

The D7 diff compiled both standards commits with `mdc-compiler/compile-standards-pack`. It compared severity,
action, always-inject, detector and globs per rule.

---

**Version History:** 0.1.0-draft (2026-10-19), initial proposal.
