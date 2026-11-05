<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# refactor(agent): Split implementer.clj into nine namespaces within the layer budget

## Overview

Part of #2378. `components/agent/src/ai/miniforge/agent/implementer.clj`
used 8 stratum layers against the rule 210 budget of 3. Any edit to it
therefore failed the pre-commit stratum gate (SL003). This PR moves its 71
top-level definitions, unchanged, into nine namespaces of at most three
layers each, and points the callers at the new namespaces. There are no
re-export shims, and behaviour does not change.

The split unblocks a #2378 fix that edits `implementer.clj`: implementer
rejection responses that drop `:cost-usd`. That fix is waiting in its
own worktree and lands on top of this PR.

## Namespaces

`ai.miniforge.agent.implementer` keeps its name and the public entry
point. The rest move under `ai.miniforge.agent.implementer.*`:

| Namespace | Defs | Contents |
|---|---|---|
| `implementer` | 4 | `create-implementer`, `invoke-with-llm`, `recover-implementer-submission`, `normalized-artifact-source` |
| `.prompt` | 17 | system prompt and prompt data, task text and its sections, user prompt, submission-retry prompt |
| `.handoff` | 5 | phase-handoff finding formatting |
| `.code` | 17 | `CodeFile` / `CodeArtifact` schemas, response parsing, code blocks, validation, language detection, summaries |
| `.language` | 3 | the extension-to-language table |
| `.repair` | 4 | artifact repair |
| `.session` | 9 | session checkpoint, base refs, progress monitors, disallowed tools, session invocation |
| `.collect` | 3 | written-file collection and result re-normalization |
| `.response` | 9 | code, already-implemented and rejection responses |

Every file is at 3 layers or fewer (`stratum-lint` clean). Each
namespace's `^{:stratum N}` annotations and `Layer N` headings were
recomputed by `stratum-lint --fix`.

## Proof that this is a pure move

The scratchpad `split-compare.clj` was run with `split-prefix` set to
`ai.miniforge.agent.implementer`. It compared every top-level `def*` form
of main's `implementer.clj` (`base/`) with the forms in the nine new
files (`new/`), with metadata stripped and references to sibling
namespaces normalized:

```text
base defs 71 | new defs 71 | identical 71 | changed 0 | removed 0 | added 0
CHANGED: ()
REMOVED: ()
ADDED: ()
```

The comparison strips metadata, so it does not show the following
changes, which are the only ones outside form bodies:

1. Visibility: 16 private vars became public because a sibling
   namespace now calls them, and Clojure refuses a cross-namespace
   reference to a private var:
   `implementer-prompt-data`, `build-effective-system-prompt`,
   `submission-retry-prompt`, `build-user-prompt`,
   `phase-handoff-findings`, `format-phase-handoff-findings`,
   `extract-extension`, `code-from-blocks`, `ext->language`,
   `implementer-disallowed-tools`, `base-ref-candidates`,
   `create-implementer-retry-progress-monitor`,
   `invoke-implementer-session`, `collect-session-artifact`,
   `normalize-implementer-result`, `process-llm-response`.
   Every other private var stays private, because only its own
   namespace uses it.
2. `^{:stratum N}` values, recomputed per file.
3. Inside form bodies, references to a moved var gained the sibling
   alias (`impl-code/parse-code-response`). The comparison normalizes
   these back to bare names.
4. New `ns` forms with docstrings and the requires each file uses. The
   rich `comment` block in `implementer.clj` now calls
   `impl-code/validate-code-artifact` and `impl-code/code-summary`.

No `::` keyword moved: all 13 auto-resolved keywords are in the forms
that stay in `ai.miniforge.agent.implementer`, so they resolve as before.

## Callers

- `interface/specialized.clj`: `CodeArtifact`, `CodeFile`,
  `code-summary`, `files-by-action`, `total-lines` and
  `validate-code-artifact` now come from `.code`.
- `interface.clj`: `implementer-task->text` now comes from `.prompt`.
- Tests: `implementer_test`, `implementer_extended_test`,
  `implementer_task_sections_test`, `parser_validation_test`,
  `response_parsing_test` and `tool_policy_test` reference the moved vars
  in their new namespaces, including `#'` references and `with-redefs`
  targets. Nothing else in the repo references the moved vars.

## Commits

1. `chore(agent): stratum annotations ...`: four files the split edits
   (`interface/specialized.clj` and three test files) had no stratum
   annotations. The first edit to such a file arms the pre-commit
   autofix over the whole file. This commit is that autofix alone, plus
   four stray dashed banners turned into `;;` comments, so the second
   commit holds only the move.
2. `refactor(agent): split implementer.clj ...`: the move and the caller
   updates.

MINIFORGE_PR_BUDGET_OVERRIDE: a namespace split moves 1,200 lines and
cannot be sliced. `lint:stratum` fails any staged copy of
`implementer.clj` still over three layers, and the file only drops under
budget when the last group leaves. Commit 1 is mechanical autofix output.
The move is proven form-for-form identical above.

## Testing Plan

- The whole `components/agent` test suite, plus
  `phase-software-factory` `implement-test` and `plan-test`.
- `clj-kondo` and `stratum-lint` are clean on every changed file.

## Deployment Plan

No configuration or behaviour change.

## Related Issues/PRs

- #2378, and its PRs #2383, #2388 and #2390.
- `phase_software_factory/implement.clj` (7 layers) needs the same split.
  It is on hold while open PR #2277 changes that file.
