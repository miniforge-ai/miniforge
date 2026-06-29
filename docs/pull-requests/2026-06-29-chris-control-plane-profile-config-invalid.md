<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Control-Plane Profile Guard Invalid

## Summary

Mark missing control-plane state profile resources as explicit invalid-config
setup failures.

## Motivation

The control-plane state machine loads its profile from a compiled classpath EDN
resource. If that resource is absent, the deploy artifact or configuration is
invalid; callers should see the same invalid-config marker used by other
resource/config guard cleanups.

## Changes

- Add `:config/error :invalid-config` to missing state profile exceptions.
- Add regression coverage for the missing profile resource ex-data.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.control-plane.interface-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.control-plane.interface-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :control-plane (count (filter #(= "components/control-plane/src/ai/miniforge/control_plane/state_machine.clj" (:file %)) cleanup))))'
```

```bash
bb pre-commit
```
