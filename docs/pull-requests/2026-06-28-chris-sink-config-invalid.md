<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Sink Config Guards Invalid

## Summary

Mark event-stream and logging invalid sink configuration guards as explicit
invalid-config setup failures.

## Motivation

Both sink factories reject non-keyword, non-map, non-vector configuration values
during sink construction. Those failures are configuration contract violations,
not ordinary runtime recoverable conditions, so their thrown data should carry
the same invalid-config marker used by the other config guard cleanups.

## Changes

- Add `:config/error :invalid-config` to event-stream invalid sink config
  anomalies.
- Add `:config/error :invalid-config` to logging invalid sink config
  exceptions.
- Add regression coverage for both ex-data shapes.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.event-stream.anomaly.sinks-anomaly-test 'ai.miniforge.logging.sinks-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.event-stream.anomaly.sinks-anomaly-test 'ai.miniforge.logging.sinks-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :sink-config (count (filter #(contains? #{"components/event-stream/src/ai/miniforge/event_stream/sinks.clj" "components/logging/src/ai/miniforge/logging/sinks.clj"} (:file %)) cleanup))))'
```

```bash
bb pre-commit
```
