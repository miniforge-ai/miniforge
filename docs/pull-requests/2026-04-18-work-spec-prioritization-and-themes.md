<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Work-spec prioritization, themes, and authoring standard

## Context

60+ specs in `work/` with priority only in chat threads and human heads.
Agents picking up work couldn't sort. New agent-created specs didn't
follow a consistent shape — some had `:normative-refs`, most didn't;
none had priority or theme metadata; acceptance criteria quality was
uneven.

This PR adds the routing layer + the authoring contract:

- `:spec/priority` block on every work spec (tier, axes, theme,
  rationale, blocks, blocked-by)
- Theme catalog at `work/themes.edn` grouping specs into epics, each
  anchored to an informative spec and normative refs
- First informative spec `specs/informative/I-DOGFOOD-RESILIENCE.md`
  rolling up 6 related work specs into one vision
- `bb work:queue` + `bb work:theme` rendering the sorted queue to stdout
  and to `work/QUEUE.md`
- Authoring standard at Dewey **`021`** in the `.standards` submodule
  (see standards PR miniforge-ai/miniforge-standards#9) — compiled
  into the pack so agents creating specs get the contract auto-injected

## What changed

### Routing layer

- `work/themes.edn` — 5 seeded themes: `dogfood-resilience`,
  `context-quality`, `polylith-compliance`, `dag-orchestration`,
  `reliability-taxonomy`. Each carries `:theme/informative-spec`,
  `:theme/normative-refs`, `:theme/completion-criteria`,
  `:theme/status`, `:theme/owner`.
- 6 Tier-1 work specs migrated with `:spec/priority` blocks:
  `plan-from-agent-dag-wiring`,
  `workflow-phase-checkpoint-and-resume`, `event-log-tool-visibility`,
  `agent-stream-watchdog-and-resume`,
  `worktree-persistence-scratch-branch`,
  `workflow-dependency-declarations`. All carry `:tier :blocker` or
  `:high` with `:blocks` / `:blocked-by` edges forming the ready-graph.
- Remaining 56 specs default to `:tier :medium :theme :unassigned` at
  render time. Backfill as specs are touched.

### Informative vision

- `specs/informative/I-DOGFOOD-RESILIENCE.md` — authoritative "what this
  theme is trying to achieve" doc. Three narrative scenarios (rate-limit
  mid-implement, process-death mid-verify, narrative-only LLM
  regression) + scope + completion criteria + cross-theme relationships
  - open questions.

### Tooling

- `tasks/work.clj` — new `work:queue` and `work:theme` commands.
- `bb.edn` — registers the commands.
- `work/QUEUE.md` — auto-generated view. Agents and humans open this
  to find the next ready `:blocker`. Sorted by theme → ready-first →
  tier asc.

Sample output (for the dogfood-resilience theme):

```text
| tier    | r | theme              | spec                                   | axes |
| blocker | ● | dogfood-resilience | event-log-tool-visibility              | observation+dogfoodenabler |
| blocker | ● | dogfood-resilience | worktree-persistence-scratch-branch    | dogfoodenabler |
| high    | ○ | dogfood-resilience | workflow-dependency-declarations       | dogfoodenabler |
| blocker | ○ | dogfood-resilience | agent-stream-watchdog-and-resume       | tokenconservation+dogfoodenabler |
| blocker | ○ | dogfood-resilience | workflow-phase-checkpoint-and-resume   | tokenconservation+dogfoodenabler |
```

`●` = ready (no unmet `:blocked-by`). `○` = waiting.

### Authoring standard (Dewey 021)

- Lands in standards PR miniforge-ai/miniforge-standards#9.
- `foundations/work-spec-authoring.mdc` — required fields, required
  `:spec/priority` block semantics, theme-must-exist check, normative-
  anchor requirement, testable-acceptance-criteria rule, scope-as-
  paths, invalid examples, good example, agent behavior spelled out.
- Submodule pointer bumped in this PR to the feature branch; will
  re-bump to main once the standards PR merges.
- `components/phase/resources/packs/miniforge-standards.pack.edn`
  recompiled: **25 → 26 rules.**

## Why now

Three forces converging:

1. **Agents creating specs.** Earlier today we opened PRs where I
   hand-authored work specs with `:spec/intent :type :fix` — invalid,
   runtime rejected. The authoring standard, compiled into the pack,
   catches this at authoring time instead of at `bb miniforge run`.
2. **Miniforge creating specs.** Eventually miniforge itself proposes
   work specs from observed failures. Without the standard it produces
   the same shape drift we've seen in humans.
3. **Priority inflation pressure.** As the queue grows, every spec
   author wants `:tier :blocker`. Making the tier visible via
   `bb work:queue` (with rationale) puts social pressure on accurate
   tiering.

## Test plan

- [x] `bb work:queue` — 62 specs rendered across 3 populated + 1
      unassigned theme; 6 Tier-1 specs appear at top of their themes
      with correct ready/waiting markers
- [x] `bb work:theme dogfood-resilience` — returns the 5 specs in that
      theme
- [x] `bb work:theme --help` — lists the 5 seeded themes
- [x] `bb standards:pack` — 26 rules, includes work-spec-authoring
- [x] `:spec/priority` block validates as EDN on all 6 migrated specs
- [ ] Standards PR merges → re-bump submodule pointer
- [ ] Next 5 work specs authored (by anyone) follow the 021 contract

## Follow-up

- **Backfill remaining 56 specs** with `:spec/priority` blocks. Ideally
  when each is next touched. Batch passes as we tackle the themes.
- **`:unassigned` specs** need a theme home — most likely fit under
  existing themes, a few (TUI, dashboard, credentials) want their own.
- **Validation at spec-parser load** — eventually the runtime should
  malli-validate `:spec/priority` and reject non-conforming specs.
  Out of scope here; standard enforces socially first.
- **`work/QUEUE.md` regeneration** — could wire into a pre-commit hook
  that runs `bb work:queue` when any `work/*.spec.edn` or
  `work/themes.edn` changes. Deferred pending agreement on pre-commit
  scope.

## References

- Standards PR: miniforge-ai/miniforge-standards#9 (Dewey 021)
- Parent authority: `foundations/specification-standards` (Dewey 020)
