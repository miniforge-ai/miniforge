<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# Policy enforcement gap analysis + program

Status: draft 2026-06-18. Drives a multi-wave program. The trust boundary:
**every defined policy is applied, and nothing that violates a policy makes it
through the workflow.** A policy that only warns is a contradiction — that is
guidance, a different kind of thing.

## Decisions (2026-06-18, from user)

These override the first-pass tiers below where they conflict.

- **Concrete rule ⇒ policy ⇒ hard-halt.** A rule's concreteness (does the
  standard define it concretely?) and the detector's reliability are different
  axes. `stratified-design` and `code-quality` are concrete → **hard-halt**, not
  human-approval. Detector false-positive risk is a *detector-quality* problem,
  solved by concrete criteria + required `file:line` evidence-to-fire + the
  ratchet (flip to hard-halt once compliant, monitor FP rate) — never by demoting
  the rule. Default any objectively-checkable rule to policy; reserve guidance for
  things that are not a property of the artifact (reviewer-behavior meta) or pure
  philosophy with no concrete criteria.
- **`require-approval` ≠ flaky-judge bucket.** It is for policies where
  *exceptions are legitimate and need human authorization* (e.g. a host bind-mount
  outside the allowlist). A deliberate per-rule call about whether exceptions
  exist, independent of detector reliability.
- **Security rules → hard-halt, flipped immediately** (not ratcheted). Accept
  short-term blocking for security.
- **New: a security pack** encoding defense-in-depth, including AI security
  (prompt-injection, tool-use boundaries, secret handling, data-exfiltration,
  model/supply-chain). Its own deliverable, same compile/gate machinery.
- **Semantic scope = in-scope (changed) files only.** And: *if a file is in
  scope, we fix the gaps we find and gate on them* — an in-scope file must fully
  comply; a violation there blocks → gets fixed → re-gates.

## 1. Findings (how enforcement actually works today)

Source of truth: `standards/miniforge/` submodule MDC files → compiled by
`bb standards:pack` (`components/policy-pack/src/ai/miniforge/policy_pack/mdc_compiler.clj`) into
`components/phase/resources/packs/miniforge-standards.pack.edn` (+ a small
`miniforge-builtin.pack.edn`). 58 rules total, all enabled.

Two enforcement surfaces:

1. **Reviewer LLM prompt injection.** `load-and-filter-behaviors :review`
   appends each applicable rule's `:rule/agent-behavior` + `:rule/knowledge-content`
   to the reviewer's prompt. Soft: enforcement depends on the model noticing and
   the reviewer choosing to reject. 55 of 58 rules reach `:review`.
2. **Deterministic policy gates** `:policy-verify` / `:policy-review`
   (`components/gate/src/ai/miniforge/gate/policy_pack.clj`), wired into the phase gate lists in
   `phase-software-factory/resources/config/phase/defaults.edn`, run via
   `apply-gate-validation`. A rule BLOCKS iff `enabled? ∧ resolved-detector ≠ :none
   ∧ enforcement :action = :hard-halt`.

### The gaps

- **Action is a mechanical default, not a decision.** `mdc_compiler.clj` derives
  `enforcement.action` as: frontmatter override → else `alwaysApply`→`:warn` →
  else `:audit`. Nobody has decided, per rule, "policy (block) vs guidance."
  Current distribution: `:hard-halt 1`, `:warn 26`, `:audit 31`.
- **Only ONE rule blocks:** `:std/clojure-no-requiring-resolve` (content-scan,
  hard-halt). Everything else is non-blocking.
- **The semantic gate is dormant in production.** 54 of 58 rules use `:custom`
  detection → resolve to a `:semantic` (LLM-judge) detector. The judge only runs
  when the gate context carries BOTH `:llm-client` AND `:complete-fn`.
  `create-workflow-context` sets `:llm-client`; **`:complete-fn` is set nowhere
  in production** (only in `detection_test`). So `with-semantic-wiring` never
  injects the judge, `detect-semantic` returns nil, and all 54 semantic rules
  silently pass the gate on every run. A seam built and unit-tested, never
  connected. Fail-OPEN.
- **Security policies are warnings.** `:std/runtime-no-host-docker-socket`,
  `:std/runtime-require-image-digest-pin`, `:std/runtime-require-rootless`,
  `:std/runtime-restrict-host-mounts` are all `:warn` — and being `:custom`/
  semantic, dormant. Container-security policy is currently unenforced by the gate.
- **Two rules enforced nowhere:** `:400-check-existing-work`,
  `:420-structural-validity` — implement-only, never reach a gate phase.

Net: exactly one policy is deterministically guaranteed to block. The other 57
depend on an LLM (the reviewer) noticing. No dead detectors, pack compiles clean
— so this is not "broken," it is "soft by construction."

## 2. The model: policy vs guidance

Each standard is exactly one of:

- **P-det — deterministic policy.** Objectively detectable (regex / structured /
  diff). → `detection.pattern` (or a custom-fn) + `enforcement.action: hard-halt`.
  Cheap, reliable, auto-blocks. No LLM.
- **P-sem — semantic policy.** Objective violation but needs code understanding.
  → `hard-halt` via the (to-be-wired) semantic gate, **fail-closed**, scoped to
  changed files. Blocks.
- **P-appr — approval-gated policy.** Objective but high false-positive risk or
  context-dependent. → `enforcement.action: require-approval` — a human confirms;
  nothing passes unchecked, but a flaky judge does not auto-brick the pipeline.
- **G — guidance.** Subjective / stylistic / process / architectural-judgment.
  NOT a gate. Injected to authoring agents + surfaced to the reviewer. Needs a
  new "guidance" tier so it is not gated at all (today the only non-blocking
  options are warn/audit, which still run the detector).
- **META — not enforcement.** Index / rule-format. Exclude from the gate set.

Guiding test: *can a violation be defined objectively enough that blocking on it
will not routinely block correct work?* Yes → policy. No → guidance.

## 3. Per-rule classification (first pass)

Marked `?` where the rule body must be read to confirm during its wave.

### P-det — deterministic policy → hard-halt (cheap)

- `:std/clojure-no-requiring-resolve` — DONE (already hard-halt).
- `:std/header-copyright` — copyright header present (regex).
- `:std/datever` — version format (regex).
- `:std/pre-commit-discipline` — detect `--no-verify` / hook bypass (regex).
- `:std/bb-over-shell` — detect `scripts/*.sh` build tasks (path/structured).
- `:std/tests-with-code` — prod files changed without test files (diff analysis).
- `:std/rust-unsafe` — `unsafe` blocks outside allowlist (regex, rust globs).
- `:std/runtime-require-image-digest-pin` — `:image-digest` not `sha256:<64hex>` (structured). SECURITY.
- `:std/runtime-no-host-docker-socket` — docker/containerd socket bind mount (structured). SECURITY.
- `:std/runtime-restrict-host-mounts` — host bind mount outside allowlist (structured). SECURITY.
- `:std/runtime-require-rootless` — resolved runtime advertises rootful (structured). SECURITY.

### P-sem — semantic policy → hard-halt via wired judge, fail-closed

- `:std/stratified-design`, `:std/code-quality` (concrete per the standards →
  hard-halt; detector reliability handled by evidence + ratchet, not demotion),
- `:std/exceptions-as-data`, `:std/result-handling`, `:std/named-constants`,
  `:std/no-dead-code`, `:std/localization`, `:std/config-as-data`,
  `:std/validation-boundaries`, `:std/clojure-exception-handling`,
  `:std/layered-architecture`, `:std/polylith`, `:std/polylith-composition`,
  `:std/tests-with-code` (semantic completeness beyond the diff check),
  `:std/standards` (testing standards), `:std/browser-security` (SECURITY),
  `:std/410-test-coverage-required` (consolidate with tests-with-code).
- Language structural rules within their globs: `:std/rust`, `:std/rust-async`,
  `:std/rust-wire-protocols`, `:std/rust-miniforge-shape`, `:std/rust-observability`,
  `:std/swift`, `:std/python`, `:std/javascript`, `:std/css`, `:std/html`,
  `:std/fulcro`, `:std/fulcro-rad`, `:std/kubernetes`. (Style-only portions → G.)
- `:std/clojure` — content-scan part is P-det; remainder P-sem.
- `:std/420-structural-validity` — likely redundant with the verify build/test gates; confirm, then fold in or drop. ?

### P-appr — approval-gated: policies with LEGITIMATE exceptions needing human sign-off

Not a flaky-judge bucket (see Decisions). Only rules where an exception can be
valid but must be authorized:

- `:std/runtime-restrict-host-mounts` — host mount outside the allowlist: block
  unless a human authorizes the specific mount. (The never-allowed mounts —
  docker socket — stay P-det hard-halt.)
- Candidates surfaced during the Standards wave where the rule itself admits
  authorized exceptions. Default remains hard-halt; this tier is opt-in per rule.

`:std/stratified-design` and `:std/code-quality` are NOT here — they are concrete
policies → P-sem hard-halt (moved per Decisions).

### G — guidance (not gated; agent-injected; needs guidance tier)

- `:std/self-documenting-code`, `:std/documentation-discipline`,
  `:std/simple-made-easy`, `:std/code-review-rigor`, `:std/web-architecture-mode`,
  `:std/polylith-tool`.
- Process: `:std/pr-layering`, `:std/pr-documentation`,
  `:std/git-branch-management`, `:std/git-worktrees`, `:std/work-spec-authoring`,
  `:std/specification-standards`.
- `:400-check-existing-work` (implementer nudge).
- Style-only portions of the language rules.

### META — exclude from gate set

- `:std/index`, `:std/rule-format`.

## 4. Wiring B so the promise holds

To make semantic policies actually block without bricking on judge flakiness:

1. **Activate the judge.** In `with-semantic-wiring`, synthesize `:complete-fn`
   from the `:llm-client` already in the workflow context (the analyzer needs
   `(llm-client, complete-fn, repo-path, rule)`). One change turns semantic
   detection on in production.
2. **Scope to the change.** `semantic-analyzer/analyze-rule` currently selects
   files across the whole `repo-path`. For a per-phase gate that is unaffordable
   — restrict detection to the artifact's changed files. Without this, gate cost
   and latency explode.
3. **Fail closed for hard-halt rules.** Today: no client → silent pass; judge
   error/timeout → recorded `:semantic-error` (non-blocking). For a `:hard-halt`
   rule that *applies* but cannot be evaluated, the gate must FAIL (block) — you
   cannot certify compliance you did not check. This mirrors the reviewer
   context-overflow fix (can't-check ⇒ don't-pass). Requires a tri-state from the
   judge: violated / clean / undetermined; undetermined on a hard-halt rule blocks.
4. **Evidence required.** A semantic violation must carry concrete `file:line`
   evidence to block; low-confidence / no-evidence → `undetermined` → for
   high-FP rules route to `require-approval` rather than auto-block.
5. **Guidance tier.** Add an enforcement classification the gate skips entirely
   (e.g. `enforcement.action: guidance`, or a `:rule/kind :guidance`), so
   guidance rules inject to agents without producing gate warnings or detector runs.

## 5. Waves (sequenced so running agent sessions are not red-walled)

Ratchet principle: never flip a rule to `hard-halt` until the codebase complies —
otherwise every in-flight session blocks at once. Enforcement goes green by
compliance, not by toothlessness.

- **E1 — engineering (miniforge):** wire B (items 4.1–4.5). Ship NON-blocking:
  the gate now *evaluates* semantic rules and records violations, but actions stay
  as-is, so nothing new blocks yet. Safe; enables measurement. + tests.
- **M — measure:** run the now-live semantic gate over the repo / recent agent
  outputs. Enumerate real violations per rule — the empirical "what are agents
  doing wrong."
- **S — standards (submodule):** set per-rule `enforcement.action` + add
  deterministic `detection.pattern`s per §3; mark guidance tier; exclude META.
  Cross-repo: settle the submodule PR and re-pin before the consuming change.
- **F1..Fn — fix violations:** clear the codebase violations per rule/cluster,
  batched. As each policy reaches zero violations, flip it to `hard-halt` (P-det
  first — cheap and immediate; then P-sem). Security is the exception: flip to
  hard-halt immediately, not ratcheted.
- **SP — security pack (parallel track):** author a defense-in-depth security
  pack including AI security (prompt-injection, tool-use boundaries, secret
  handling, data-exfiltration, model/supply-chain), compiled and gated by the
  same machinery. Security rules ship hard-halt from the start.

## 6. Open questions — resolved 2026-06-18

- ~~P-appr for stratified-design / code-quality?~~ No — they are concrete
  hard-halt policies (see Decisions).
- ~~Security: flip immediately or ratchet?~~ Flip immediately.
- ~~Whole-repo vs changed-files scope?~~ Changed-files only; in-scope files must
  fully comply (fix gaps + gate).

Remaining to settle during the Standards wave: the exact G-vs-policy line for the
current guidance list (default to policy for anything that is a property of the
artifact; keep only reviewer-behavior meta and no-criteria philosophy as
guidance).

## 7. Fidelity eval results (2026-06-19)

Harness + seeded corpus in `eval/policy-fidelity/`. This section records the
initial run on a 6-fixture / 5-candidate-rule / 3-trial corpus; the committed
`fixtures/truth.edn` is the later grown 12-fixture / 9-rule corpus scored in §10,
so the numbers here are historical, not the current `truth.edn` shape. (Truth in
`fixtures/truth.edn`; raw audit log regenerable, not committed.) Two methodology bugs were caught and fixed by
hardening, in order:
(a) fixture annotation comments leaked the answer key to the judge and induced
false no-dead-code hits — stripped; (b) the hand-seeded oracle was less thorough
than the judge — corrected against the real rule text (localization covers
exception payloads; named-constants requires const docstrings). Lesson: the
oracle must be at least as good as the judge.

Matrix (recall/precision against corrected truth; parse-fail = unparseable
output = miss):

| model | strategy | recall | precision | parse-fail | flaky | stable |
|---|---|---|---|---|---|---|
| sonnet-4.6 | A focused | 1.00 | 0.89 | 0 | 1 | yes |
| sonnet-4.6 | B batched | 0.82 | 0.91 | 15 | 11 | no |
| opus-4.7 | A focused | 1.00 | 0.85 | 0 | 3 | yes |
| opus-4.7 | B batched | 1.00 | 0.95 | 0 | 1 | yes |
| gpt-5.4 | A focused | 0.95 | 0.80 | 2 | 2 | ~ |
| gpt-5.4 | B batched | 0.87 | 0.90 | 10 | 11 | no |

Conclusions:

- An LLM judge with focused prompts is reliable enough to hard-gate (recall 1.00,
  stable, zero parse-fails on both Claude models) — de-risks the whole program.
- Batched-B's reliability is model-bound: it parse-fails ~a third of the time on
  sonnet/gpt but zero on opus. "Reject B" only holds below a model strong enough
  to parse the large batched prompt.
- Stronger ≠ uniformly better: opus-A precision (0.85) < sonnet-A (0.89). Best
  model is per-strategy.
- gpt-5.4 weakest on both axes here, plus codex returns raw JSONL (`:content`
  needs agent-message extraction — a real backend gap if GPT ever judges).

Two Pareto-optimal production configs (both clear recall 1.00):

- **opus-4.7 + batched-B** — precision 0.95, fewest calls (~5/gate). Best if opus
  cost is acceptable.
- **sonnet-4.6 + focused-A** — precision 0.89, cheaper model; focused call-cost
  recoverable via file-prefix caching.

Residual precision 0.05–0.15 is defensible over-reads (e.g. `:ok` flagged as
named-constants — right concern, wrong rule); handle before hard-halt via
rule-scope tightening / accept-cross-rule / evidence-required. Corpus is small;
grow rules + files before production confidence.

## 8. Locked judge config (2026-06-19) — supersedes §7's recommendation

Confirmation matrix on the production model `claude-opus-4-8` + `claude-sonnet-4-6`,
corrected truth, 6 fixtures × 5 rules × 3 trials:

| model | strategy | recall | precision | parse-fail | flaky |
|---|---|---|---|---|---|
| opus-4.8 | A focused | 1.00 | 0.95 | 0 | 2 |
| **opus-4.8** | **B batched** | **1.00** | **1.00** | **0** | **0** |
| sonnet-4.6 | A focused | 1.00 | 0.87 | 0 | 0 |
| sonnet-4.6 | B batched | 0.95 | 0.90 | 5 | 5 |

opus-4.8 + batched is flawless on this corpus (recall 1.00, precision 1.00, zero
parse-fails, zero flaky across 3 trials) and the cheapest viable config.
sonnet-batched stays parse-fail-unreliable (model-bound, ~28%→here 5/18).

**LOCKED:** `claude-opus-4-8` + **batched** (one judge call per changed file,
all applicable rules in a single prompt), **fail-closed**, **changed-files
scope**. Caveat: small corpus — grow rules/files before over-trusting precision
1.00; directionally decisive.

## 9. Cost + caching, per backend path (2026-06-19)

Analytic input-cost model in `eval/policy-fidelity/cost_model.clj` (the CLI
backend exposes no `cache_control` and reports zero usage, so caching is
modeled from token structure + published rates: write 1.25×, read 0.1×;
Opus 4.8 $5/Mtok in, Sonnet 4.6 $3/Mtok). Per review gate, 5-file PR, 33
applicable rules (count corrected after the `glob?` String→Path fix — the prior
26 dropped glob-scoped rules whose matcher silently failed):

| opus-B variant | input-tok | $/gate |
|---|---|---|
| uncached | 275K | $1.37 |
| within-gate cached | 108K | $0.54 |
| cross-run warm | 51K | $0.26 |

opus-B is 0.40× sonnet-A uncached, 0.48× cached. Cross-run, the rule-pack write
(~$0.26) amortizes once per TTL window; steady-state per-gate is dominated by the
changed files, not the rules — you pay for the diff, not the policy set.

Caching is **backend-conditional** (user decision 2026-06-19 — don't convert free
quota into metered spend):

- **API / BYOM backend** (cache_control-capable: raw Anthropic key, or
  opencode/cursor wired to a key): compiled rule-pack as the frozen cache prefix
  (rules-first, byte-stable) + `cache_control` + 1h TTL + pre-warm
  (`max_tokens: 0`). ~$0.26/gate steady-state; cross-repo cache sharing on the
  same model+org. Only **batched** exposes the rule pack as one cacheable prefix.
- **Subscription CLI** (`claude` print mode): no `cache_control`; plan-covered
  (constraint is quota, not $). Caching is a no-op. opus-B still wins: 5 calls/gate
  vs focused's 130 → ~26× less quota burn.

E1 additions implied: an API/HTTP judge backend with `cache_control` prefix
wiring, gated on backend capability (no-op on the subscription CLI); fail-closed
semantic detection; changed-files scoping.

## 10. Grown-corpus validation (2026-06-19)

Corpus grown to **12 fixtures × 9 candidate rules × 3 trials** (added
config-as-data, clojure-exception-handling, self-documenting-code [a deliberately
fuzzy rule], validation-boundaries; plus 2 clean files + 1 near-miss). Two more
rigor passes: natural code with no rule-name leaks, and fixture filenames renamed
domain-neutral (a `clean_*` / `near_miss` name would prime the judge via the path
it sees). Re-ran the locked config only (opus-4.8 batched).

opus-4.8 + batched on the grown, reconciled corpus: **recall 0.95, precision
~0.90–0.92, zero parse-fails** (vs 1.00/1.00 on the 6-file corpus). Recall is
stable at 0.952 across draws; precision varies 0.90–0.92 run-to-run (6 flaky
cells over 3 trials — an LLM judge is stochastic, not deterministic). The
1.00/1.00 was small-clean-corpus optimism. Breakdown: clear rules hold at recall
1.00; the dip is on subtle/secondary readings — `clojure-exception-handling` 0.67
(the ex-info-should-be-throw+ overlap, caught ~2/3 of trials), `localization`
0.92 (one flaky miss). Reconciliation again found the judge ≥ a hand-seeded
oracle (most raw FPs were defensible cross-rule findings my truth had missed —
added to truth). Numbers reproduced on the post-review harness (the Copilot-fix
commit: nil-safe rule coercion, map-filtered violation parsing, fail-fast on
missing candidate rules) — those guards are no-ops on opus's clean EDN output, so
fidelity is unchanged.

**Implications (do not change the lock; refine the expectation):**

- An LLM judge as a hard gate is **~95% recall, not infallible** — ~5% of
  violations (concentrated on subtle/overlapping readings) slip a single pass.
- So treat it as one strong layer, not an oracle: defense-in-depth = deterministic
  content-scan detectors where a rule allows + the reviewer-LLM layer + the
  fix-and-gate ratchet (re-gates until an in-scope file complies). Multiple passes
  / multi-lens verification can lift recall on subtle rules if needed.
- opus-B remains the locked config: zero parse-fails, strongest both-axis profile,
  cheapest viable. Corpus is still synthetic + modest — grow toward real
  agent-output samples for production-grade confidence before flipping subtle
  semantic rules to hard-halt.
