<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->
# fix(test-runner): isolate pr-sync brick to prevent shell/sh redef pollution

## Overview

Add `pr-sync` to the `default-isolated-bricks` set in
`scripts/test-changed-bricks.bb`. Eliminates a class of intermittent
test failures across `workflow`, `agent`, and `cli` bricks when their
tests run in parallel with `pr-sync`'s test JVM.

## Root cause

`pr-sync` tests use `with-redefs` on `clojure.java.shell/sh` to
install a fake "router":

```clojure
;; components/pr-sync/test/.../fleet_parallel_test.clj:34-41
(defn make-sh-router [routes]
  (fn [& args]
    (let [match (some (fn [[substr response]]
                        (when (some #(and (string? %) (.contains ^String % substr)) args)
                          response))
                      routes)]
      (or match {:exit 1 :out "" :err "no route matched"}))))
```

`with-redefs` mutates the var ROOT — globally for the JVM, not
thread-local. When the test runner uses `pmap` to run multiple
brick groups concurrently in the same JVM, pr-sync's redef of
`clojure.java.shell/sh` is observed by every other thread.

Bricks that legitimately shell out get poisoned:

- `workflow.merge-resolution-test` — `(shell/sh "git" "init" "-b" "main" ...)`
  errors with `git init -b main failed: no route matched` because
  pr-sync's mock returns `{:exit 1 :err "no route matched"}` for
  unknown commands.
- `agent.curator-merge-resolution-test` — `scan-conflicted-paths`
  calls `(shell/sh "git" "-C" path "grep" ...)` which the mock
  reports `:exit 1`. The `conflicted-paths-via-git-grep` cond branch
  `(and r (= 1 (:exit r)))` matches, returning `#{}`. `or` short-circuits
  on the (truthy) empty set, file-walk fallback never runs, and the
  curator reports `:resolution/markers-cleared? true` even though
  the test wrote conflict-marker files. Six tests in the namespace
  fail this way.
- `cli` (`gh ...` subprocesses) — same mechanism, intermittent.

## Fix

Add `pr-sync` to `default-isolated-bricks`. Isolated bricks get their
own test JVM (one JVM per brick), eliminating cross-thread root-mutation
pollution by construction.

The comment on `default-isolated-bricks` documents the failure mode and
notes the deeper fix (indirect through a project-local wrapper rather
than redef'ing `clojure.java.shell/sh` directly) as a follow-up.

## Why not a different lever

- **Affinity groups** keep `workflow`/`phase`/`agent`/`phase-software-factory`
  sequential within one JVM, but pr-sync runs in a parallel pmap
  group alongside that affinity group. Adding pr-sync to the affinity
  group would make it sequential with workflow+agent — but other
  parallel groups (`compliance-scanner`, `cli`) also call `shell/sh`,
  and pr-sync would still poison them. JVM-level isolation is the
  correct unit.
- **`binding` instead of `with-redefs`** in pr-sync tests requires
  `clojure.java.shell/sh` to be `^:dynamic`, which it isn't (and
  shouldn't be — it's a third-party var).
- **Project-local indirection** in pr-sync (a wrapper var the tests
  redef) is the cleanest long-term fix but is a larger change. The
  comment on `default-isolated-bricks` calls this out as a follow-up.

## Verification

- `clj-kondo` on the script: clean.
- Local `bb scripts/test-changed-bricks.bb` run with the fix: previously
  red (curator-merge-resolution-test 6 failures + workflow.merge-resolution-test
  errored) → green.

## Test plan

- [x] Lint passes
- [x] Local runner converges
- [ ] CI passes on the next PR that touches `pr-sync`, `agent`, or
      `workflow` (the bricks that previously colluded)
