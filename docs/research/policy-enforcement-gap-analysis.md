# Policy enforcement gap analysis + program

Status: draft 2026-06-18. Drives a multi-wave program. The trust boundary:
**every defined policy is applied, and nothing that violates a policy makes it
through the workflow.** A policy that only warns is a contradiction — that is
guidance, a different kind of thing.

## 1. Findings (how enforcement actually works today)

Source of truth: `standards/miniforge/` submodule MDC files → compiled by
`bb standards:pack` (`policy_pack/mdc_compiler.clj`) into
`components/phase/resources/packs/miniforge-standards.pack.edn` (+ a small
`miniforge-builtin.pack.edn`). 58 rules total, all enabled.

Two enforcement surfaces:

1. **Reviewer LLM prompt injection.** `load-and-filter-behaviors :review`
   appends each applicable rule's `:rule/agent-behavior` + `:rule/knowledge-content`
   to the reviewer's prompt. Soft: enforcement depends on the model noticing and
   the reviewer choosing to reject. 55 of 58 rules reach `:review`.
2. **Deterministic policy gates** `:policy-verify` / `:policy-review`
   (`gate/policy_pack.clj`), wired into the phase gate lists in
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

### P-appr — approval-gated (human confirm) — candidates, user to confirm

- `:std/stratified-design`, `:std/code-quality` — if treated as policy rather
  than guidance, gate to human approval (judge unreliable for auto-block).

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
  first — cheap and immediate; then P-sem; security rules prioritized).

## 6. Open questions for the user

- P-appr tier: do architectural-judgment rules (`stratified-design`,
  `code-quality`) become human-approval gates, or stay guidance?
- Security rules: flip to `hard-halt` first, ahead of cleaning all violations
  (accept short-term blocking for security), or ratchet like the rest?
- Whole-repo vs changed-files semantic scope at the gate — confirm changed-files
  only (recommended) for cost.
