# Stratum-Lint Baseline: Findings + Fix Waves

**Generated:** 2026-07-24
**Branch:** `claude/stratum-linter-miniforge-a86147`
**Tool:** [stratum-lint](https://github.com/miniforge-ai/stratum-lint), sha
`7ca43db35c7547fe919069e9d69ef8c5d2810042` (the pin already declared in
`tasks/lint.clj`). Enforces `languages/clojure` (210)'s per-file `Layer N`
heading convention — see `standards/miniforge/foundations/stratified-design`
and `standards/miniforge/languages/clojure`.

**Method:** Resolved the pinned dep via `bb -Sdeps` (no change to `tasks/lint.clj`
— that pin already existed; the pre-commit hook it's wired into only ever
pointed it at staged files). Ran, full-tree instead of staged-only:

```bash
bb -m stratum-lint.interface bases components projects development/src
```

Raw output (876 lines) is archived at
`work/stratum-lint-baseline-2026-07-24.findings.txt`; this document is the
analysis and remediation plan built on top of it.

## Scope

Full workspace: `bases/`, `components/`, `projects/`, `development/src`.
`.clj`/`.cljc` only (tool's own file filter). Files without any `Layer N`
heading are silently skipped by the tool — a documented limitation, not a pass.
So the true debt is **at least** what is reported here, not exactly it.

## Summary

| Check | Count | Meaning |
|-------|------:|---------|
| SL002 | 514 | Heading not strictly increasing — a `Layer N` reused as a repeated section banner instead of one heading per real stratum |
| SL003 | 203 | File has more distinct layers than the budget (3) |
| SL004 | 116 | A `def` appears before the first `Layer` heading |
| SL001 |  43 | Upward reference — a def under Layer N calls a def above Layer N |
| **Total** | **876** | across **378** files, **75 of 121** components/bases |

## Diagnosis: this is cargo-culting, confirmed

The heading convention (rule 210) asks for headings that are **true** — a
label the code crosses is "worse than no label." What's on disk instead:

1. **Headings as decoration, not structure.** `components/event-stream/src/ai/miniforge/event_stream/core.clj`
   carries 29 `Layer` headings running `0, 0, 0, 1, 0, 1, 1, 2, 3, 4×9, 5, 5.5,
   6, 6, 6.1, 6.2, 6.5, 7, 9`. Non-monotonic, non-integer (`5.5`, `6.5`), and
   "Layer 9" in a file whose own rule caps strata at 3. This is a heading
   applied per function-group as a visual break, not per abstraction level.
   Re-running the tool's own strata inference (`fix-source`, read-only, not
   applied) collapses this to **6 real levels** from the reference graph —
   still over budget, confirming the file also needs an actual split, not
   just relabeling.
2. **The same pattern dominates SL002 generally**: 514 of 876 findings are
   this — a heading repeated for the third, fourth, ninth time in one file.
   366 of the 876 total findings are in `test/` namespaces, where this is
   near-universal: each `deftest` group got its own "Layer 1" banner.

## A second finding: SL001 is not 43 real violations

Spot-checked 6 of the 18 files reporting SL001. Five were **false positives**
from the checker's scope-blind symbol walk, one was real:

- `components/response/src/ai/miniforge/response/anomaly.clj:144` —
  `retryable?`'s parameter is named `anomaly`, which shadows an unrelated
  top-level `(defn anomaly ...)` constructor defined later in the file. Not a
  call.
- `components/tool/src/ai/miniforge/tool/interface.clj:87` — same shape:
  parameter `tool-id` shadows a same-named `defn tool-id` later in the file.
- `components/supervisory-state/src/ai/miniforge/supervisory_state/core.clj:52` —
  parameter `table` shadows `defn table` at Layer 4. Confirmed by reading both
  sites.
- `components/fsm/src/ai/miniforge/fsm/core.clj:88` — same shadowing shape
  (`context`).
- `components/agent/src/ai/miniforge/agent/core.clj:218` — different cause:
  `defrecord BaseAgent`'s protocol method implementations are literally named
  `invoke`/`repair`; the checker's unscoped symbol walk can't distinguish a
  protocol method head from a call to the same-named top-level `defn`.
- `components/phase/src/ai/miniforge/phase/telemetry.clj:105` — **real**.
  `emit-phase-started!` (Layer 1) genuinely calls `emit-milestone-started!`,
  which is `declare`d at the top and only `defn`'d down at Layer 3. The
  `declare` is precisely papering over a heading that's wrong: the milestone
  emitters belong at or below Layer 1, not above it.

Implication: the checker has no lexical scoping — it flags any occurrence of
a def's name anywhere in another def's body, including as a parameter,
`let`/destructuring binding, or a `defrecord`/protocol method head. Every one
of the 18 SL001 files needs a human read before a fix, not a mechanical one.
This is worth filing upstream against `stratum-lint` itself, separately from
this cleanup.

## Findings by component (top offenders)

| Component | Total | SL001 | SL002 | SL003 | SL004 |
|-----------|------:|------:|------:|------:|------:|
| `components/agent` | 65 | 2 | 27 | 20 | 16 |
| `components/dag-executor` | 63 | 5 | 49 | 6 | 3 |
| `components/event-stream` | 62 | 0 | 43 | 11 | 8 |
| `components/progress-detector` | 58 | 0 | 52 | 6 | 0 |
| `components/loop` | 40 | 0 | 31 | 1 | 8 |
| `components/workflow` | 39 | 0 | 6 | 19 | 14 |
| `components/pr-lifecycle` | 38 | 0 | 30 | 6 | 2 |
| `components/policy-pack` | 37 | 0 | 32 | 5 | 0 |
| `components/repo-index` | 36 | 0 | 35 | 1 | 0 |
| `bases/cli` | 28 | 0 | 10 | 10 | 8 |
| `components/compliance-scanner` | 25 | 0 | 21 | 1 | 3 |
| `components/automation-edge-correlator` | 21 | 7 | 13 | 1 | 0 |
| `components/tui-views` | 20 | 0 | 6 | 7 | 7 |
| `components/evidence-bundle` | 19 | 0 | 12 | 7 | 0 |
| `bases/mcp-context-server` | 17 | 2 | 13 | 1 | 1 |
| `components/web-dashboard` | 15 | 0 | 8 | 5 | 2 |
| `components/knowledge` | 14 | 0 | 4 | 8 | 2 |
| `components/phase` | 13 | 4 | 6 | 3 | 0 |
| `components/operator` | 13 | 0 | 8 | 5 | 0 |
| `components/reliability` | 12 | 0 | 11 | 1 | 0 |
| `components/connector-github` | 12 | 0 | 2 | 0 | 10 |
| `components/bb-platform` | 12 | 4 | 8 | 0 | 0 |

The remaining ~54 components/bases each carry 1–11 findings — the full
per-component pivot (all 75) is in
`work/stratum-lint-baseline-2026-07-24.findings.txt`, groupable with:

```bash
awk -F: '{print $1}' work/stratum-lint-baseline-2026-07-24.findings.txt \
  | sed -E 's#^(bases|components|projects)/([^/]+)/.*#\1/\2#' | sort | uniq -c | sort -rn
```

## The 18 files with SL001 (need individual human triage)

| File | Findings | Sampled? | Verdict |
|------|---------:|----------|---------|
| `bases/lsp-mcp-bridge/.../lsp/client.clj` | 1 | no | unverified |
| `bases/mcp-context-server/.../context_cache.clj` | 2 | no | unverified |
| `components/agent/.../core.clj` | 2 | yes | false positive — protocol method head (`invoke`/`repair`) |
| `components/automation-edge-correlator/.../core.clj` | 7 | no | unverified — same `pure-state` target every time; likely one real design question, not 7 |
| `components/bb-platform/.../core.clj` | 4 | no | unverified — same `installed?` target every time |
| `components/dag-executor/.../descriptor.clj` | 5 | no | unverified — same `kind`/`capabilities` targets; smells like the shadowing pattern (`kind` as a common param name) |
| `components/diagnosis/.../engine.clj` | 2 | no | unverified |
| `components/fsm/.../core.clj` | 1 | yes | false positive — `context` param shadows a later `context` def |
| `components/gate-classification/.../core.clj` | 2 | no | unverified |
| `components/phase-deployment/.../config_resolver.clj` | 1 | no | unverified |
| `components/phase/.../telemetry.clj` | 4 | yes | **real** — milestone emitters mislabeled above their only caller |
| `components/response-chain/.../core.clj` | 2 | no | unverified — same `steps` target; smells like shadowing |
| `components/response/.../anomaly.clj` | 2 | yes | false positive — `anomaly` param shadows the `anomaly` constructor |
| `components/supervisory-state/.../core.clj` | 2 | yes | false positive — `table` param shadows `defn table` |
| `components/task/.../interface.clj` | 1 | no | unverified |
| `components/tool-registry/.../lsp/client.clj` | 2 | no | unverified — mirrors the lsp-mcp-bridge client, probably same shape |
| `components/tool/.../interface.clj` | 2 | yes | false positive — `tool-id` param shadows `defn tool-id` |
| `components/tui-engine/.../layout/buffer.clj` | 1 | no | unverified |

6 of 18 verified; 5 false positive (shadowing/method-head), 1 real. The
repeated-target pattern (`pure-state`, `installed?`, `kind`, `steps` each
hit by every finding in their file) matches the shadowing signature closely
enough to predict most of the remaining 12 will resolve the same way, but
that's a prediction, not a finding — each still needs the same two-minute
read before being touched.

## Fix waves

Ordered so each wave de-risks the next. PR-discipline (722) still applies:
<400 lines, one stratum per PR — several of these waves are themselves
several PRs, not one.

### Wave 0 — close the enforcement gap (small, unblocks everything else)

**Decided:** no new CI step. Enforcement stays in pre-commit, strengthened
rather than duplicated elsewhere — a report-only CI job would just be a
second place for the same signal to be ignored. Two parts, in order:

1. File a PR against `miniforge-ai/stratum-lint` fixing the SL001
   false-positive class documented above (unscoped symbol walk conflating
   shadowed params/defrecord protocol-method heads with real calls). Merge
   there first, then bump the pin in `tasks/lint.clj`.
2. Once the pin is bumped, evaluate `--fix` output quality across the
   codebase. If it's reliable, change `bb lint:stratum` (`tasks/lint.clj`'s
   `stratum-staged`) to run `--fix` on staged files automatically — the same
   shape as `fmt:md-staged`'s `lint --fix` — instead of failing and asking
   the developer to hand-fix headings. That closes the loop for good: a
   commit that would have introduced a decorative heading gets normalized
   before it lands, not just rejected.

`bb lint:stratum:all` (full-tree invocation, matching `lint:clj:all`'s
shape) is still worth adding as a manual/ad-hoc task for re-baselining
during Waves 1-4 — just not as a second enforcement point.

### Wave 1 — mechanical relabeling via `--fix`, decorative-heading files only

Target: files with SL002/SL003/SL004 findings and **zero** SL001 (i.e., no
upward-reference or cycle risk to reason about first). `--fix` recomputes
each def's real stratum from the same-file reference graph and regroups —
verified safe to run (no exceptions, no SL007 cycles) on the worst offender
(`event-stream/core.clj`, 29 headings → 6). It **does** reorder defs in the
file, so every PR needs a real diff read, not a blind merge — batch by
component, smallest reference graphs first to build confidence:
`compliance-scanner`, `reliability`, `gate`, `decision`, `adapter-claude-code`
before the big ones (`progress-detector`, `policy-pack`, `repo-index`,
`pr-lifecycle`, `event-stream`).

Test-file-only findings (366 of 876) are lower risk than production
(reordering `deftest` forms doesn't change behavior) — can run as a separate,
larger-batched sub-wave in parallel with production files, per component.

### Wave 2 — real namespace splits

After Wave 1, some files will still trip SL003 because the file genuinely has
more than 3 real strata (not decorative — `event-stream/core.clj` will land
at 6). These need the actual namespace-splitting strategy from rule 210
(related-functions → subnamespace, unrelated → separate namespace), not
another `--fix` pass. Candidates: re-run the full lint after Wave 1 and take
whatever still reports SL003. `components/workflow` (19 SL003 findings
already, pre-fix) and `bases/cli` (10) are likely heavy here given their
existing size.

### Wave 3 — SL001 triage, one file at a time

The 18 files above. Each needs the read `phase/telemetry.clj` got here: is
the "reference" a shadowed parameter/binding, a protocol-method head, or a
genuine call to a higher-numbered def? False positives get no code change
(or, if it's worth silencing, a rename to stop the collision). Real ones
(the `telemetry.clj` shape) get the higher-numbered def moved down — usually
means it was never a "higher" concept, just placed later in the file. Small
blast radius per file; can run independently of Waves 1/2. Once Wave 0's
upstream fix lands, re-run the lint first — the tool doing lexical scoping
will have already cleared most of this wave's false positives, shrinking
the file list to whatever's left.

### Wave 4 — pre-heading defs (SL004)

116 findings, concentrated in `agent` (16), `workflow` (14), `loop` (8),
`bases/cli` (8), `connector-github` (10, all one shape presumably), `tui-views`
(7). Mechanical: either the def belongs in Layer 0 (add the missing heading
above it) or it's genuinely a namespace-level constant that predates any
`Layer` heading and should be pulled under one. No architectural judgment
needed — can bundle into whichever wave (1 or 2) is already touching that
file.

### Wave 5 — confirm the gate holds

Once a full-tree run reports zero findings and Wave 0's autofix wiring is
live, there's no separate report-only step to flip — pre-commit already
enforces at every commit. Wave 5 is just verification: re-run the full-tree
invocation once more to confirm zero findings, and confirm the autofixing
`bb lint:stratum` actually catches a deliberately-reintroduced decorative
heading in a scratch commit before calling the gate closed.

## Decisions (resolved 2026-07-24)

- **PR granularity:** one component per PR, matching the
  `exception-cleanup-inventory.md` precedent.
- **Upstream fix sequencing:** stratum-lint's false-positive fix is filed and
  merged *before* Wave 1 starts, not in parallel — Wave 0 above.
- **Enforcement location:** pre-commit only, autofix-first (see Wave 0). No
  new CI workflow.
