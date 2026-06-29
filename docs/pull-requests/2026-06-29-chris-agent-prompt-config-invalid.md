<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Mark Agent Prompt Config Guards Invalid

## Summary

Mark missing or malformed agent prompt resources as explicit invalid-config
setup failures.

## Motivation

Agent prompts are compiled classpath resources. A missing prompt EDN file, or a
prompt EDN file without the required `:prompt/system` key, is a packaging or
configuration contract violation rather than an ordinary runtime not-found
condition. The thrown anomaly data should carry the same invalid-config marker
used by the other resource/config guard cleanups.

## Changes

- Add `:config/error :invalid-config` to missing prompt resource anomalies.
- Add `:config/error :invalid-config` to malformed prompt data anomalies.
- Add regression coverage for both prompt loader entry points.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.agent.prompts-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.agent.prompts-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :agent-prompts (count (filter #(= "components/agent/src/ai/miniforge/agent/prompts.clj" (:file %)) cleanup))))'
```

```bash
bb pre-commit
```
