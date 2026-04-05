---
title: Compliance Scanner
description: Build a Miniforge capability that scans a repository against compiled policy packs, produces a compliance
delta report, generates a DAG remediation plan, and executes fixes autonomously via the dag-executor.
acceptance_criteria: Scan detects policy violations by rule-id, classify adds auto-fixable flag and rationale, plan
generates DAG tasks and markdown work spec, execute applies auto-fixable fixes via dag-executor and opens PRs
tags: [compliance, policy, scanner, dag, remediation]
---

## Vision

Given a repository and a set of policy packs, Miniforge:

1. **Scans** the repo for all violations across every applicable standard
2. **Classifies** each violation as auto-fixable or needs-human-review
3. **Outputs** a delta report (what's failing) and a remediation work spec (what to do)
4. **Executes** the plan via the DAG executor — parallel across files, sequential within a file

This makes standards compliance a repeatable, automated operation rather than a manual one-time pass. Miniforge applies
it to its own codebase first (dogfood), then exposes it as a product feature for any repository.

---

## Inputs

| Input | Type | Description |
|---|---|---|
| `repo-path` | string | Path to the repository root |
| `standards-path` | string | Path to `.standards/` submodule (or remote URL) |
| `policy-selector` | keyword/set/`:all` | Which packs to apply; default `:always-apply` |
| `mode` | keyword | `:scan` \| `:plan` \| `:execute` |
| `since` | string | Optional git ref; enable incremental mode (changed files only) |

**`policy-selector` options:**

- `:always-apply` — all packs with `alwaysApply: true` in frontmatter (default)
- `:all` — every `.mdc` file in the standards path
- `#{"dewey.210" "dewey.810"}` — explicit set of Dewey codes
- `"dewey.2xx"` — prefix wildcard (all language rules)

---

## Outputs

### 1. Compliance Delta Report (EDN)

Machine-readable. Stored at `.miniforge/compliance-report.edn` after each scan.

```edn
{:repo-path        "/path/to/repo"
 :standards-ref    "abc123"         ; git SHA of standards submodule
 :scan-timestamp   "2026-04-04T..."
 :mode             :plan
 :summary          {:total-violations 142
                    :auto-fixable     118
                    :needs-review      24
                    :files-affected    61
                    :rules-violated     9}
 :violations       [{:rule/dewey      "210"
                     :rule/title      "Clojure Map Access"
                     :file            "components/foo/src/..."
                     :line            42
                     :current         "(or (:k m) default)"
                     :suggested       "(get m :k default)"
                     :auto-fixable?   true
                     :rationale       "Literal default, non-JSON field"}
                    {:rule/dewey      "210"
                     :rule/title      "Clojure Map Access"
                     :file            "components/server/src/..."
                     :line            88
                     :current         "(or (:type d) \"choice\")"
                     :suggested       nil
                     :auto-fixable?   false
                     :rationale       "JSON-deserialized map; key can be present with nil value"}
                    ...]}
```

### 2. Remediation Work Spec (Markdown)

Human-readable plan document. Stored at `docs/compliance/YYYY-MM-DD-compliance-delta.md`.

Format designed to be fed directly back to Miniforge as a work input — the same format as any other phase input spec.
Sections:

- **Executive summary** — violation counts by rule, files affected
- **Auto-fixable violations** — grouped by Dewey code, with file lists and patterns
- **Needs-review violations** — grouped by Dewey code, with rationale for each exception
- **Execution instructions** — DAG topology, recommended PR structure, ordering constraints

### 3. DAG Definition (EDN)

Produced by the plan phase. Passed directly to `dag-executor/execute-dag`. Not persisted long-term.

---

## Architecture

The compliance workflow runs as a Miniforge workflow with four phases.

### Phase 1: Scan

One scan agent per policy pack, running in parallel.

Each agent:

1. Loads the `.mdc` file → compiled `Rule` via `policy-pack/mdc-compiler`
2. Filters files via `repo-index/files-by-language` and the rule's `applicability` patterns
3. Runs detection against each file

**Detection strategy by rule type:**

| Rule category | Detection method |
|---|---|
| Mechanical patterns (Dewey 210, 810) | Regex scan via existing `Scanner` protocol |
| Structural conventions (Dewey 300, 400) | AST-level or pattern matching |
| Semantic/design rules (Dewey 003, 004, 010) | LLM-powered analysis; violations returned as `:needs-review` |

Each scan agent returns a sequence of violation maps for its rule. All rule scans run in parallel — they share only the
read-only repo index.

### Phase 2: Classify

Aggregate violation lists from all scan agents. For each violation, determine:

- `auto-fixable?` — Is the substitution safe without semantic review?
- `rationale` — One-line explanation (surfaces in the delta report and PR description)

**Auto-fixable when ALL hold:**

1. The fix is a mechanical substitution (no context-dependent judgment)
2. The rule doc explicitly marks the pattern as safe-to-automate
3. The violation does not match any documented semantic exception

**Not auto-fixable when ANY hold:**

1. The rule requires reasoning about runtime behavior or type semantics
2. The specific instance matches a known edge case (e.g., JSON-deserialized maps, `or` as nil-coerce)
3. The rule category is Dewey 003, 004, or 010 (semantic design rules)

The classification step is itself agent-powered for mixed rules — a classification agent reviews each candidate
violation for the edge-case patterns documented in the rule body.

### Phase 3: Plan

Convert the classified violation list into a DAG of remediation tasks.

**DAG topology:**

- **Node** = `(file × rule)` — apply one rule's violations to one file atomically
- **Edge** = same-file serialization — two nodes sharing a file run sequentially in Dewey code order (lowest Dewey first
  = most foundational rules applied first)
- Auto-fixable nodes → executable agent tasks
- Needs-review nodes → human-approval gates (DAG pauses, waits for confirmation)

**Node execution order within a file:**
Apply rules in ascending Dewey order to ensure deterministic application and respect rule hierarchy (architectural rules
before language rules before project rules).

**Output:**

- The remediation work spec (markdown) written to `docs/compliance/`
- An in-memory DAG definition passed to Phase 4

### Phase 4: Execute (`:execute` mode only)

Feed the DAG to `dag-executor/execute-dag`. Each task node:

1. Acquires file lock via `dag-executor/acquire-file-locks!`
2. Runs a targeted code-edit agent with context:
   - The file content (from `repo-index/get-file`)
   - The rule body (the `.mdc` file)
   - The specific violation list for this `(file × rule)` pair
3. Agent applies all violations for this pair atomically (one edit pass)
4. Releases file lock
5. Transitions task to `:implemented`

**PR structure:**
One PR per rule (matching the Dewey 210 PR pattern). Configurable to one PR per brick or one PR for all. Each PR title
follows: `fix: [Dewey NNN] <rule-title> compliance pass`.

---

## Dogfood Path

The first consumer is Miniforge itself:

1. **Build** the compliance scanner (this spec)
2. **Run** on the miniforge repo with `policy-selector :always-apply`
3. **Review** the delta report — see the full outstanding compliance picture
4. **Execute** — DAG executor applies auto-fixable violations in parallel across the codebase
5. **Review gate** — needs-review violations surface as human-approval gates; decide case by case
6. **Merge** — one PR per Dewey code, reviewable in isolation
7. **Prevent regression** — compliance scanner runs on every PR as a pre-merge check

This replaces the current manual per-standards-doc pass we just completed for Dewey 210.

---

## Components

### New

| Component | Role |
|---|---|
| `compliance-scanner` | Orchestrates scan → classify → plan workflow; emits delta report and work spec |
| `workflow-compliance-scan` | Workflow type definition; wires compliance phases into the workflow runner |

### Extended

| Component | Extension |
|---|---|
| `policy-pack` | LLM-powered `Scanner` implementation alongside existing regex scanner |
| `phase-software-factory` | `:compliance-fix` phase variant — targeted single-file edit with rule context |

### Used As-Is

`repo-index` (file discovery), `dag-executor` (scheduling + file locking + parallel execution), `workflow` (lifecycle),
`policy-pack` (MDC compiler, rule loading, scanner protocol).

---

## Incremental Mode

After the first full scan:

1. Store the compliance baseline at `.miniforge/compliance-baseline.edn`
2. Subsequent runs accept `since: <git-ref>`
3. Only scan files changed since that ref
4. Re-check unchanged files for regressions (fast: compare against baseline)
5. Delta = new violations in changed files + any regressions detected

This makes compliance checking fast enough to run on every PR.

---

## Auto-Fixability Reference

Initial classification per existing standards:

| Dewey | Rule | Auto-fixable? | Notes |
|---|---|---|---|
| 001 | Stratified Design | No | Structural/architectural judgment |
| 002 | Code Quality | Partial | Mechanical patterns yes; design patterns no |
| 003 | Result Handling | No | Semantic; requires error-path reasoning |
| 004 | Validation Boundaries | No | Semantic; requires boundary identification |
| 010 | Simple Made Easy | No | Design judgment |
| 020 | Specification Standards | No | Document structure judgment |
| 050 | Localization | Partial | Missing i18n keys yes; structural patterns no |
| 210 | Clojure Map Access | Yes | Except JSON-deserialized nil edge cases |
| 220 | Python Standards | Yes | Where mechanical pattern applies |
| 300 | Polylith | Partial | Interface violations yes; dep structure needs review |
| 320 | Kubernetes | No | Infrastructure judgment |
| 400 | Testing Standards | Partial | Naming/structure yes; coverage judgment no |
| 710 | Git Branch Management | No | Workflow judgment |
| 715 | Pre-commit Discipline | No | Process, not code |
| 721 | PR Documentation | Partial | Missing fields yes; quality judgment no |
| 722 | PR Layering | No | Judgment call |
| 725 | Git Worktrees | No | Workflow judgment |
| 730 | Datever | Yes | Date format substitution, fully mechanical |
| 810 | Copyright Header | Yes | Missing/malformed headers, fully mechanical |
| 900 | Meta | No | Standards authoring rules |

---

## Relationship to Quality Readiness Assessment

The compliance scanner is a **sub-evidence source** for the Quality Readiness Assessment workflow
(`specs/informative/miniforge_quality_readiness_workflow_spec_v2.md`).

The two capabilities are orthogonal but compose:

| | Compliance Scanner | Quality Readiness Assessment |
|---|---|---|
| Question | "Does this file follow rule X?" | "Is this thing production-ready?" |
| Method | Pattern detection + regex | Rubric-scored evidence evaluation |
| Output | Violation list + fix DAG | Scorecard + routing decision |
| Scope | Code patterns, headers, naming | Test strategy, security, observability, product clarity |

**How they connect:** The compliance scanner's delta report becomes evidence in the `:quality-evidence` domain of the
  quality readiness workflow. A repo with many open violations scores lower on that domain. A clean compliance scan is
  positive evidence toward `:green`.

**Execution relationship:**

```text
Quality Readiness Assessment
  └── `:quality-evidence` domain pulls from:
       ├── compliance-scanner delta report (this component)
       ├── test results / coverage reports
       └── performance baselines
```

**Implementation sequencing:** The compliance scanner (M1–M5) should ship before the quality readiness workflow (Q1–Q3),
  since the QRA workflow consumes compliance scanner output as a sub-evidence source. Both share `repo-index`,
  `policy-pack`, and `dag-executor` infrastructure.

---

## Open Questions

1. **Semantic scanner LLM prompt design** — How do we structure the rule body + file content as an LLM prompt that
  reliably produces structured violation maps? Starting point: few-shot examples derived from the Dewey 210 manual pass.

2. **Classification confidence threshold** — For LLM-classified violations, what confidence level is required before
  marking `auto-fixable? true`? First pass: conservative (default to needs-review; promote to auto-fixable only with
  high confidence + explicit rule annotation).

3. **Cross-repo fleet compliance** — Same scanner pointed at multiple repos; aggregate delta across a fleet. Out of
  scope for v1; the architecture supports it (standards-path is a parameter).

4. **Standards authoring loop** — After running the scanner, easy to see which rules have zero violations (already
  compliant) vs many violations (rule was never enforced). Could surface this as "rule coverage" to guide standards
  maintenance.
