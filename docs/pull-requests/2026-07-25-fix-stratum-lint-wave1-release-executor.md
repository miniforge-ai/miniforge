# fix: stratum-lint autofix for components/release-executor (Wave 1)

## Overview

Runs `stratum-lint --fix` over `components/release-executor` (`src` +
`test`) to replace decorative `Layer N` headings with real ones derived
from each file's actual same-file reference graph. Mechanical: no logic
changes. One component from `work/stratum-lint-baseline-2026-07-24.md`'s
Wave 1 batch 3.

## Motivation

`release-executor` carried exactly 2 findings under the baseline's
cargo-cult diagnosis, both `SL002` in `git.clj` (a repeated `Layer 0`
heading, and a repeated `Layer 1` heading — decorative section breaks,
not real stratum boundaries). Zero `SL001` findings for the component, so
it matched Wave 1's selection criteria: no upward-reference/cycle risk to
reason about before running the mechanical fixer.

## Changes in Detail

Ran, over the whole component, at stratum-lint pin
`14965e1ee1a175bd00f637d9a9d5f7d27e62b73f` (bumped in #1501):

```bash
bb -Sdeps '{:deps {io.github.miniforge-ai/stratum-lint {:git/sha "14965e1ee1a175bd00f637d9a9d5f7d27e62b73f" :deps/root "clojure"}}}' -m stratum-lint.interface --fix components/release-executor
```

17 files rewritten (`--fix` normalizes every already-annotated file in the
component, not just the 2-finding one): `core.clj`, `files.clj`, `git.clj`,
`interface.clj`, `messages.clj`, `metadata.clj`, `result.clj`, `sandbox.clj`
(`src`), and 9 test files. `git.clj`'s two flagged headings collapsed into
the file's real `Layer 0`/`Layer 1`/`Layer 2` structure. Elsewhere: pure
heading/metadata/reordering, no executable-code changes.

### A `defmethod`-handling bug in `--fix`, found and fixed upstream mid-PR

An earlier pass at the previous pin (`80699e378cb8ebbb6daeb928431aa4a6b373c07e`)
surfaced a real `--fix` defect: `files.clj`'s `process-file-action` (a
`defmulti` + 4 `defmethod`s) genuinely calls `path-traversal-anomaly` and
`write-file!`, both defined later in the file, but `--fix` placed
`process-file-action` at `Layer 0` — below its own callees. This was
self-inconsistent (the plain lint's own `SL001` check flagged the `--fix`
output it had just produced) and broke `clj-kondo` with real
`Unresolved symbol` forward-reference errors, since `--fix` physically
reorders defs by its computed layer.

Root cause (per upstream triage): `infer-levels`'s dependency graph was
keyed by def name via `into {}` — every `defmethod` for one multimethod
shares the multimethod's own name, so a repeated key meant `into {}` kept
only the *last* `defmethod`'s refs, discarding the earlier ones'.
`process-file-action`'s `:default` method (last in file, refs nothing)
silently overwrote `:create`/`:modify`/`:delete`'s real refs into
`path-traversal-anomaly`, so the multimethod was computed as a leaf and
placed before what it actually calls.

Fixed upstream in
[stratum-lint#14](https://github.com/miniforge-ai/stratum-lint/pull/14)
(union refs per name instead of overwrite; regression test added and
verified to actually catch the bug), pin bumped in #1501. Re-ran this
component's fix fresh at the new pin (full re-baseline, not just
`files.clj`) — `process-file-action` now correctly lands at `Layer 3`,
after `path-traversal-anomaly` (`Layer 2`) and `write-file!` (`Layer 1`).
`clj-kondo` is clean on `files.clj` and the rest of the component.

### Hand-fixed: stale non-integer heading banners

Three files (still, at the new pin — unrelated to the `defmethod` bug
above) carry a pre-existing, non-standard `Layer 1.5` / `Layer 0b`
heading that `--fix`'s heading parser doesn't recognize (it only matches
bare integers), so it leaves them untouched, now sitting between the
tool's new real `Layer 0`/`Layer 1` headings — non-monotonic and
confusing (`0 → 1.5 → 1 → 2` in `git.clj` and `sandbox.clj`; `0 → 0b → 1
→ ...` in `metadata.clj`). In all three cases the descriptive text (`PR
creation helpers (mirrors sandbox.clj equivalents)`, `Diff inspection...`,
`Workflow data extraction`) was worth keeping as a plain grouping comment
— matches the style of other same-layer grouping comments already in
those files — so only the false `Layer N` claim was deleted, not the
description. Re-ran `--fix` after each edit: stable (zero diff on a
subsequent pass).

### Unrelated pre-existing clj-kondo warning

`core_sandbox_test.clj` had one pre-existing `unused binding opts-seen`
warning (present before this fix too, just at a different line after
reordering) — a dead `let` binding never referenced in its test body.
Removed it so the component lints clean, per this PR's own bar.

### Eight real bugs found by automated review, fixed in `sandbox.clj`, `git.clj`, and `core.clj`

Automated review on this PR flagged eight genuine, pre-existing
correctness issues across several rounds (unrelated to the stratum-lint
mechanics above, but small enough to fold into this PR rather than open a
second one). Verified each directly against the code before fixing:

1. **`commit-changes!` reported a phantom success on a `rev-parse`
   failure.** After a successful `git commit`, it ran `git rev-parse HEAD`
   and put `(:output sha-r "")` straight into `:commit-sha` via
   `result/shell-success`, without checking whether `rev-parse` itself
   succeeded. A `rev-parse` failure still reported `:success? true` with
   an empty/missing `:commit-sha` — misleading to any caller expecting a
   real sha. Fixed: check `sha-r`'s own success first; on failure, return
   `shell-failure` with `:commit-sha nil` instead.

2. **`push-with-https-fallback!` interpolated a token-bearing URL into an
   unescaped shell string, and swallowed both `set-url` calls' results.**
   `ssh->https-with-token` embeds a literal access token
   (`https://x-access-token:TOKEN@host/path`), and `exec!` routes string
   commands through `sh -c` (see `dag-executor/workspace.clj`'s `exec-fn`
   contract) — so `(str "git remote set-url origin " https-url)` was a
   real injection/credential-exposure surface, not just a style nit, the
   same class of thing `commit-changes!`/`stage-files!` already guard
   against for commit messages and file paths. Worse, neither the
   repoint-to-https call nor the restore-to-original call checked its own
   result: a failed repoint let the push proceed against a remote that
   was never actually changed, and a failed restore left the token
   persisted in git config with no signal. Fixed by passing both
   `git remote set-url` calls as argv vectors (`["git" "remote" "set-url"
   "origin" https-url]`) instead of a shell string — `exec!`/
   `executor-execute!` already accept either form, and a vector never
   touches `sh -c` — and checking both results: a failed repoint aborts
   before pushing, and a failed restore fails loud (surfacing a scrub
   command) even when the push itself succeeded, mirroring
   `git/with-https-token-fallback!`'s existing shape and reusing its
   `:push/https-fallback-*` message catalog entries.

3. **`git.clj`'s `commit-changes!` has the exact same `rev-parse`
   phantom-success bug as `sandbox.clj`'s (#1 above) — a separate host-mode
   implementation, not a shared function, so the same defect was
   duplicated rather than inherited.** Same fix pattern: check
   `(zero? (:exit sha-r))` before returning `shell-success`; on failure,
   return `shell-failure` with `:commit-sha nil`.

4. **`sandbox.clj`'s `exec!` omitted `:output` entirely on its
   executor-error branch** (`(result/shell-failure (str "Executor error: "
   (:error r)))`, no extras map) — the docstring promises `{:success? bool
   :output string :error string}` always, and the two branches above it
   (command success/failure) both include `:output`, but `shell-failure`
   without a data map produces a result with no `:output` key at all (not
   even `nil` — `merge` just never adds it). Fixed: pass `{:output ""}` as
   the extras map, matching the sibling branches' shape (no partial output
   exists to report for an executor-level failure, as opposed to a
   command-level one).

5. **`git.clj`'s `exec!` has the same `:output`-omission gap on its
   `catch` branch** (`(result/shell-failure (.getMessage e))`, no extras
   map) — same docstring contract, same fix: `{:output ""}` (no partial
   output is available once `process/shell` has thrown).

6. **`push-branch!`/`push-with-https-fallback!`'s three non-push `exec!`
   calls — the `git remote get-url origin` probe and the repoint/restore
   `set-url` calls — all passed `{}` instead of the caller's `opts`, while
   the push itself (correctly) used `opts`.** `opts` can carry `:workdir`
   (per `exec!`'s own docstring), so a caller-specified working directory
   applied to the push but silently dropped for the other three calls
   would leave them running against a different executor context than the
   push they're resolving/bracketing. Fixed by passing `opts` to all four
   `exec!` calls uniformly — nothing in `opts` (currently just `:env` at
   the only call site) is push-specific in a way that would be wrong to
   apply to the others too. (Automated review flagged the repoint/restore
   pair and the `get-url` probe as two related comments; both are the same
   defect class and are fixed together here.)

7. **`stage-files!` interpolated file paths into a shell command string
   without validating them first**, unlike its siblings `write-file!` and
   `delete-file!` in the same file, which both guard with
   `validate-safe-container-path` before single-quote-wrapping a path. An
   unvalidated path containing an embedded single quote would break out of
   the `'...'` wrapping — a real command-injection vector, not a style
   nit, since `exec!` routes string commands through `sh -c`. Fixed by
   validating every path with `validate-safe-container-path` (same guard
   already used elsewhere in this file) before building the command;
   returns the validation failure directly if any path is unsafe.

8. **`core.clj`'s `provenance-frontmatter` built YAML frontmatter via raw
   string concatenation**, with no escaping of YAML-significant
   characters in provenance values (workflow/spec/task ids, commit sha).
   A value containing `:` (colon-space), a leading `-` (YAML
   block-sequence indicator), a `#` (comment marker), or an embedded
   newline could produce invalid or ambiguously-parsed frontmatter in the
   committed PR doc. Added a `yaml-scalar` helper: values matching a
   conservative safe pattern (start with an alphanumeric, then
   `[A-Za-z0-9._/-]*` — covers the common case of ids/paths/shas) render
   as an unquoted YAML plain scalar; anything else renders as a
   double-quoted scalar with backslashes/quotes escaped and newlines
   collapsed to `\n`. Caught my own first draft of this fix during
   testing: an initial regex allowed a *leading* `-` through as "safe"
   (a bare `-x` is technically a valid YAML plain scalar in most parsers,
   but exactly the ambiguous case the review comment called out), so the
   regex now requires the first character to be alphanumeric,
   pushing anything hyphen-led into the quoted branch.

Added 10 regression tests: 5 in `sandbox_test.clj`
(`commit-changes-rev-parse-failure-test`,
`push-branch-https-setup-failure-test`,
`push-branch-https-restore-failure-test`,
`exec-executor-error-includes-output-test`,
`push-with-https-fallback-threads-opts-test`) plus 1 more
(`stage-files-rejects-unsafe-path-test`), following the existing
tracking-exec mock pattern already used by
`push-branch-ssh-fail-https-fallback-test` in the same file; 2 in
`git_test.clj` (`commit-changes-rev-parse-failure-test`,
`exec-bang-exception-includes-output-test`), following that file's
existing `with-redefs [process/shell ...]` pattern (used by
`gh-token-injection-test`/`force-push-injects-token-test`) rather than the
real-git-repo round-trip pattern, since simulating a `rev-parse` failure
or a thrown exception right after/during a real git invocation isn't a
reachable real-git state to construct directly; 2 in
`core_sandbox_test.clj` (`provenance-frontmatter-escapes-yaml-significant-characters`,
`provenance-frontmatter-escapes-leading-dash-and-newline`). Each assertion
targets behavior only the fixed code produces — verified by inspection
(and, for the `yaml-scalar` leading-dash regex, by an actual test failure
during development that caught the gap) that all ten would fail against
the pre-fix code.

## Testing Plan

1. Ran plain (non-`--fix`) `stratum-lint` before the fix — reproduced the
   baseline's 2 `SL002` findings in `git.clj` exactly, 0 elsewhere.
2. Ran `--fix`, then a second `--fix` pass immediately after — zero diff,
   confirms idempotency.
3. Read the full diff for all 17 changed files. Found and hand-fixed the
   stale `Layer 1.5`/`Layer 0b` banners (3 occurrences, 3 files); no
   same-line trailing comment was displaced onto the wrong def elsewhere.
4. Re-ran `--fix` after the heading hand-fixes: zero diff (stable).
5. `clj-kondo --lint components/release-executor`: 0 errors, 0 warnings,
   including `files.clj`.
6. Fixed the eight review-flagged bugs (above) and added 10 regression
   tests. Ran all 9 test namespaces directly via `clojure -A:dev:test -e
   "(require ...) (clojure.test/run-tests ...)"`: 255 tests, 641
   assertions, 0 failures, 0 errors.
7. Re-ran plain `stratum-lint` after the fix: `SL001`/`SL002`/`SL004`
   clear. `SL003` newly surfaced on 5 files, all real over-budget files
   the old decorative headings under-reported (none of these 5 reported
   any finding in the baseline):
   - `core.clj` — 5 real layers (max 3)
   - `files.clj` — 5 real layers
   - `metadata.clj` — 6 real layers
   - `sandbox.clj` — 5 real layers
   - `test/pr_body_test.clj` — 4 real layers

   All deferred to Wave 2 (real namespace split), consistent with how
   prior Wave 1 PRs handled the same situation.

## Deployment Plan

Merges to `main` like any other component change. Almost entirely
comment/metadata/order-only; the eight bug fixes above are real behavior
changes (a failure that used to be silently swallowed, misreported as
success, dropped from the result shape, or run against the wrong executor
context now surfaces/behaves correctly; an unvalidated path or an
unescaped provenance value can no longer corrupt a shell command or a
committed YAML frontmatter), scoped to the HTTPS-token-fallback push
path, the post-commit sha lookup, the generic `exec!` error/exception
branches, `stage-files!`, and `provenance-frontmatter`, all covered by
new tests. `MINIFORGE_STRATUM_BUDGET_MODE=warn` is required at commit
time for the 5 newly-surfaced `SL003` files above.

## Related Issues/PRs

- Baseline: `work/stratum-lint-baseline-2026-07-24.md` (Wave 1)
- Follow-on: Wave 2 namespace splits for `core.clj`, `files.clj`,
  `metadata.clj`, `sandbox.clj`, and `test/pr_body_test.clj` (all over the
  3-layer budget)
- Upstream fix: [stratum-lint#14](https://github.com/miniforge-ai/stratum-lint/pull/14)
  (`defmethod` refs-union fix), pin bump #1501 — both merged before this
  PR's final commit

## Checklist

- [x] `--fix` run over the whole component (`src` + `test`), at the
      current (post-#1501) stratum-lint pin
- [x] Second `--fix` pass confirms idempotency (zero diff)
- [x] Diff read in full for all 17 changed files
- [x] Stale `Layer 1.5`/`Layer 0b` banners hand-fixed (3 occurrences),
      re-confirmed stable
- [x] `clj-kondo` clean (0 errors, 0 warnings) across every changed file,
      including `files.clj`
- [x] Eight review-flagged bugs fixed: `sandbox.clj`'s `commit-changes!`
      phantom success on `rev-parse` failure; `sandbox.clj`'s
      `push-with-https-fallback!` unescaped token URL + unchecked
      `set-url`/restore results + unthreaded `opts` (including the
      `get-url` probe); `git.clj`'s `commit-changes!` sibling `rev-parse`
      bug; `sandbox.clj`'s and `git.clj`'s `exec!` both omitting `:output`
      on their error/exception branch; `sandbox.clj`'s `stage-files!`
      unvalidated shell-injection surface; `core.clj`'s
      `provenance-frontmatter` unescaped YAML injection surface. 10
      regression tests added.
- [x] Component tests pass (255 tests, 641 assertions, 0 failures/errors)
- [x] Plain lint re-run post-fix: zero findings except 5 newly-surfaced
      `SL003` files, documented above, tracked as Wave 2
- [x] No `--no-verify`; pre-commit hook runs normally at commit time
