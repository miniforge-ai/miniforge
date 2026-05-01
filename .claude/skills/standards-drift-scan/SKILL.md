---
name: standards-drift-scan
description: Sweep the miniforge codebase for drift against `.standards/**/*.mdc`, then open one focused PR per coherent
theme. Use when the user asks to "scan for standards drift", "run the drift scan", "audit against miniforge-standards",
or invokes `/standards-drift-scan`.
argument-hint: [theme]
allowed-tools: [Read, Edit, Write, Bash, Grep, Glob, Agent]
---

# Standards Drift Scan

Recurring quality pass over the miniforge repo. Audits the working tree against
the canonical standards pack at `.standards/**/*.mdc` and opens one focused PR
per coherent drift theme. The aim is a steady stream of small, mergeable
cleanup PRs — not a single sweeping refactor.

## When to invoke

- User runs `/standards-drift-scan` (no arg → pick highest-signal theme).
- User runs `/standards-drift-scan <theme>` to target a specific area
  (`localization`, `factories`, `requiring-resolve`, `anomalies`, `headers`).
- User says "scan for drift", "run the drift sweep", or asks for a recurring
  cleanup pass.

## Working assumptions

- Working directory: `/Users/chris/ws/miniforge.ai/miniforge`.
- Always work in a `.claude/worktrees/<short-name>` git worktree branched off
  `origin/main`. Never on main directly. Per `feedback_always_use_worktrees.md`.
- One PR per theme. Three-PR cap per run unless a clearly trivial bundled
  cleanup falls out.
- `bb pre-commit` must pass before every push. No `--no-verify`.
- Exceptions are data, not throws — per `feedback_exceptions_are_data.md`.

## Themes (pick the one with the most signal — or the user-specified arg)

### 1. Localization

Raw user-visible English strings in `(println …)`, `(display/print-* …)`, or
`(log/info logger _ _ "raw text")` sites that should resolve via a
`messages/t` catalog key.

Excludes:

- JSON/EDN protocol output (e.g., `hook-eval` decisions) — those are wire
  formats, not UI.
- Comment blocks (`(comment …)`).
- Structured logger field keys like `:lsp/started` — those identify the
  event, not user-visible copy. The strings *adjacent* to them may still
  need migration; judge case by case.

For each migration:

- Add keys to the component's `resources/config/<comp>/messages/en-US.edn`.
- Wire them through the existing `messages/create-translator` helper if the
  component already has one; otherwise add a thin `messages.clj` per the
  pattern in `components/gate/messages.clj` or `bases/etl/src/.../messages.clj`.
- Update call sites to `(messages/t :key {…})`.

### 2. Factory functions in tests

Per `.standards/testing/standards.mdc`: any test data map constructed more
than once, or any map with more than 3 fields, must come from a `defn-`
factory. Look for repeated inline `{:event/* …}` / `{:task/* …}` /
`{:pr/* …}` shapes in a single `_test.clj` file.

Heuristic for picking a target:

1. Run `git grep -n '{:[a-z-]\+/' components/*/test/**/*_test.clj` and look
   for files with 3+ matches of the same key prefix.
2. Skip files that already have a factory covering the dominant shape.
3. Prefer contained component-test files over integration tests.

Existing examples to mirror: `code-artifact` / `plan-artifact` factories in
`components/agent/test/.../artifact_session_test.clj`; `pr-info` factory in
`components/pr-lifecycle/test/.../controller_test.clj`.

### 3. `requiring-resolve` smell

Per `feedback_avoid_requiring_resolve.md`: lazy-resolving around load-order
issues is an agent-smell; the fix is the underlying design, not a deferred
lookup. Find call sites with `git grep -n 'requiring-resolve' components bases`,
filter out tests, and surface ones that look like load-order workarounds (a
hard `:require` would have caused a cycle).

### 4. Exceptions as data

Per `feedback_exceptions_are_data.md`: component interfaces should return
anomaly data, not throw. Catch only at absolute boundaries. Look for
`(throw (ex-info …))` inside component `interface.clj` files; refactor to
return `:anomalies/...` maps and propagate via the response component.

### 5. Headers / copyright

Per `.standards/project/header-copyright.mdc`: every Clojure source / test /
resource EDN file under `components/**`, `bases/**`, `projects/**` needs the
canonical Apache-2.0 header. Find missing headers with:

```bash
git grep -L "Apache License" -- 'components/**/*.clj' 'bases/**/*.clj'
```

## Workflow

```text
1. Pull latest main.
2. Spawn an Explore agent (very thorough) to identify the top 3 candidate
   files for the chosen theme. Ask for absolute paths, the violation
   pattern, and any obvious blockers.
3. Pick the cleanest candidate.
4. Create a worktree on origin/main: `chris/<theme>-<scope>` naming.
5. Make the change in one focused commit.
6. Run `bb pre-commit` — bail out and fix any failure before pushing.
7. Push + open PR with a `## Summary` / `## Test plan` body in the same
   shape as #719, #720, #722, #724, #725.
8. Loop steps 2–7 until you've opened up to 3 PRs or `bb review` reports
   zero drift across all themes.
```

After the PR is open:

- If Copilot leaves comments on first CI pass, address them in a `review:`
  follow-up commit, then `gh api` reply + resolve each thread.
- Don't merge — leave that to the human reviewer.

## Hard limits

- Three PRs per run, max.
- Don't touch any branch you didn't create in this run.
- If `bb review` reports zero drift across every theme, post a one-line
  summary ("no drift found across [themes]") and exit without opening a PR.
- If you hit a CI failure or merge conflict you can't auto-resolve, leave a
  note on the worktree, skip that PR, and continue with the next theme. Do
  not block the run waiting for the user.

## Reference PRs (good shape to mirror)

- #719 cli/main `status-cmd` — single-file localization
- #720 etl base — new translator + catalog + smoke test
- #722 cli/scan — large multi-string localization with composite templates
- #724 agent artifact-session — factory functions with overrides
- #725 pr-lifecycle controller — factory + shadowing avoidance

## Output

End with a one-paragraph summary listing each opened PR, its theme, and the
review state if known. If nothing was opened, say so explicitly.
