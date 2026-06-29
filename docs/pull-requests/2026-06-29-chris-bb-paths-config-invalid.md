<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark BB Paths Repo Root Guard Invalid

## Summary

Mark the missing `bb.edn` repo-root discovery guard as an explicit
invalid-config setup failure.

## Motivation

`bb-paths/repo-root` is a local setup boundary: it discovers the workspace root
by walking up from cwd until it finds `bb.edn`. If no marker exists, the command
is running outside a Miniforge checkout or in a malformed workspace, so the
exception data should carry the invalid-config marker used by the other setup
guard cleanups.

## Changes

- Add `:config/error :invalid-config` to the missing `bb.edn` exception.
- Add scratch-cwd regression coverage for the ex-data shape.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.bb-paths.core-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.bb-paths.core-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :bb-paths (count (filter #(= "components/bb-paths/src/ai/miniforge/bb_paths/core.clj" (:file %)) cleanup))))'
```

```bash
bb pre-commit
```
