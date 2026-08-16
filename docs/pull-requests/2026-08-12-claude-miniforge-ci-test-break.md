<!--
  Title: Restore the OPSV fixture fallback in the project integration run
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# fix(test): restore OPSV fixture fallback in project integration run

## Overview

`main` has been red on the `Test` job since #1678. The miniforge
project integration suite aborts at namespace load with:

```
Execution error (ExceptionInfo) at
  ai.miniforge.workflow.opsv-lifecycle-support/fn
  (opsv_lifecycle_support.clj:65).
OPSV application fixture not found
❌ Integration tests failed in project: miniforge
```

This restores the working-directory-relative fixture lookup that #1678
dropped, so `ai.miniforge.workflow.opsv-lifecycle-support` loads under
the project integration runner again.

## Motivation

Two runners load this fixture, and they resolve it differently:

- The workspace `:test` alias lists
  `components/phase-opsv/test-resources` in `:extra-paths`
  (`deps.edn`), so `(io/resource "opsv/application-fixture.edn")`
  resolves there.
- `tasks/test_runner.clj`'s `run-project-tests!` launches
  `clojure -Sdeps projects/miniforge/deps.edn -M -e …` with `:dir`
  set to `projects/miniforge`. That deps.edn's `:paths` are
  `["test" "integration" "e2e"]` and no component contributes a
  `test-resources` path, so the classpath lookup returns `nil`.

#1677 registered
`ai.miniforge.workflow.opsv-lifecycle-integration-test` in the
integration list and handled the second case with an `or` fallback to
`../../components/phase-opsv/test-resources`, relative to the runner's
working directory. #1678 then extracted the fixture out of the test
namespace into the new `opsv_lifecycle_support.clj` and carried over
only the `io/resource` branch — which is precisely the branch that
never resolves under this runner. The `ex-info` guard introduced
alongside it turned the resulting `nil` into a load-time throw, so the
whole miniforge integration suite aborts before a single test runs.

`3ba2d9f` (#1678) is the commit named in the body of issue #1679, and
every red run since has the same signature.

## Changes in Detail

| File/Area | Change |
|-----------|--------|
| `projects/miniforge/test/ai/miniforge/workflow/opsv_lifecycle_support.clj` | Restore the `project-relative` fallback beside the `io/resource` lookup; comment why both branches exist; add the attempted relative path to the `ex-info` data |

The restored resolution is byte-for-byte the logic #1677 shipped green.
Two things are new on top of it:

- A comment recording that the two branches serve two different
  runners, so the next extraction of this `def` keeps them together
  rather than treating the fallback as redundant.
- `:fixture/project-relative` in the `ex-data`, so a future
  recurrence names the path it looked for instead of only the
  classpath-relative resource name.

No test logic changed; the def set of the namespace is unchanged.

## Testing

The full suite could not be run in this environment: `repo.clojars.org`
is blocked by the sandbox network policy (CONNECT → 403), so the
project's Clojars-hosted dependencies cannot resolve and neither
`bb test` nor `bb test:integration` can start. What was verified
instead, with a Maven-Central-only Clojure:

- [x] Reader/syntax check of the edited namespace — 18 top-level forms
      read clean.
- [x] The fixture-resolution logic executed with the working directory
      set to `projects/miniforge`, exactly as `run-project-tests!`
      invokes it: `io/resource` returns `nil` (confirming the root
      cause), the fallback resolves to
      `../../components/phase-opsv/test-resources/opsv/application-fixture.edn`,
      and the file parses as EDN.
- [x] The parsed fixture carries `:experiment-pack`, the only key the
      support namespace reads out of it (`workflow-input`,
      `legacy-adapter-context`); the whole map is what
      `simulated/create-adapter` receives.
- [x] Regression bounded to this one namespace: it is the only
      `io/resource` caller under `projects/miniforge`, and
      `components/phase-opsv/test/…/test_support.clj` — the other
      reader of this fixture — runs under the workspace `:test` alias,
      where the classpath branch resolves and is untouched.

CI on this PR is the real check: it runs `bb test:all`, which is the
job that has been failing.

## Deployment Plan

Merge to `main`. The next fully green `main` push auto-closes #1679.

## Related

- Fixes the break introduced by #1678 (`3ba2d9f`)
- Restores the fallback added in #1677 (`78bc50b`)
- Related to #1679 (CI is red on main)

## Checklist

- [x] Root cause identified and attributed to a specific commit
- [x] Fix restores previously-green behavior rather than masking the
      symptom
- [x] Fixture resolution verified under the failing runner's working
      directory
- [ ] `bb test` / `bb test:integration` — not runnable here (Clojars
      blocked by sandbox network policy); delegated to CI on this PR
