# refactor(cli): split workflow_runner/display.clj — extract display namespaces (rule 210, 1/2)

## Overview

`bases/cli/src/ai/miniforge/cli/workflow_runner/display.clj` carries 8 real
strata against the rule-210 budget of 3 — the worst SL003 offender left in
`workflow_runner/`. This is PR 1 of 2 that splits it into ten sibling
namespaces, each a named vocabulary of ≤3 strata.

**PR 1 (this one) — extraction.** Create the ten new namespaces containing the
moved code. `display.clj` is not touched; the new namespaces are standalone and
nothing requires them yet.

**PR 2 (follow-up) — the flip.** Rewrite `display.clj` to require the new
namespaces, delete the moved definitions, and re-export the public vars its
callers use.

## Motivation

Two constraints force the two-PR shape:

- **SL003 is a staged-file gate.** The pre-commit stratum-lint run only sees
  files in the commit. Because this PR never stages `display.clj`, its existing
  8-stratum violation is not re-reported, and no budget override is needed.
- **600-reportable-line PR budget.** Extraction plus flip in one PR would carry
  both the new files and the rewrite of `display.clj` in one diff. Split in two,
  each half stays inside the budget.

## Changes in Detail

New files under `bases/cli/src/ai/miniforge/cli/workflow_runner/`:

| Namespace (`…workflow-runner.`) | Vocabulary | Strata |
|---|---|---|
| `display-ansi` | ANSI escape-code styling | 2 |
| `display-format` | scalar → display-string conversions | 1 |
| `display-event-line` | colorized lifecycle-event lines | 3 |
| `display-demo-line` | plain-text (ANSI-stripped) event lines | 2 |
| `display-progress` | live progress via event-stream subscription | 1 |
| `display-result-facts` | pure readers over a workflow result | 3 |
| `display-summary-lines` | individual compact-summary lines | 3 |
| `display-summary` | compact-summary assembly | 2 |
| `display-print` | header / summary / result printing | 2 |
| `display-error-help` | operator-facing failure help | 1 |

Dependency graph among the new namespaces (one-way, no cycles):

```text
display-ansi ────┬─> display-event-line ─┬─> display-demo-line
display-format ──┘                       └─> display-progress
                 ├─> display-error-help
                 └─> display-summary-lines ─> display-summary ─> display-print
display-result-facts ──> display-summary-lines
```

Definitions moved verbatim except for:

- `strip-ansi` and `humanize-keyword` change from `defn-` to `defn` — they are
  now consumed across a namespace boundary.
- The `compact-*` line builders in `display-summary-lines` become public and
  drop the `compact-` prefix (`compact-status-line` → `status-line`, and so on);
  the namespace name now carries that qualifier, so `lines/status-line` reads
  without stutter.

## Testing Plan

- `stratum-lint` (pin `bef8657`), plain and `--fix` on copies, over all ten new
  files: clean, and `--fix` proposes **zero** changes — the hand-written
  `^{:stratum n}` metadata and `Layer N` headings match the strata computed from
  each file's reference graph, and every file is within the 3-stratum budget.
- `clj-kondo`: 0 errors, 0 warnings.
- Behavioural equivalence harness: 152 paired comparisons of every moved public
  function against its `display.clj` original — ANSI codes, `colorize` over 7
  colors, `format-duration` at each boundary, `format-event-line` and
  `format-demo-line` over 40 event shapes, the four extractors, compact-summary
  assembly, all print functions captured via `with-out-str`, and
  `start-progress!` driven over a real event stream. **0 mismatches.**
- `display-test` + `display-output-test`: 74 tests, 168 assertions, 0 failures,
  0 errors. Neither namespace changes in this PR; the run proves the new files
  do not interfere.

## Deployment Plan

No behaviour change and no runtime effect: the new namespaces are not required
by anything until PR 2. Ships with the ordinary merge to `main`.

## Related Issues/PRs

- Follow-up: PR 2 rewires `display.clj` and deletes the moved code.
- Rules: `standards/miniforge/languages/clojure` (210),
  `standards/miniforge/foundations/stratified-design` (001).

## Checklist

- [x] Ten new namespaces created, each ≤3 strata
- [x] Apache header on every new file
- [x] `^{:stratum n}` metadata agrees with `Layer N` headings
- [x] stratum-lint plain + `--fix` dry run clean
- [x] clj-kondo clean
- [x] Behavioural equivalence verified against `display.clj`
- [x] `display-test` / `display-output-test` green
- [ ] PR 2: flip `display.clj` to require + re-export, delete moved code
