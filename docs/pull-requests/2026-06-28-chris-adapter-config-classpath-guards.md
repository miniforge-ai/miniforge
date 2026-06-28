<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Adapter Config Classpath Guards

## Summary

Make Claude Code adapter config-shape failures explicit classpath config guards.

## Motivation

`ai.miniforge.adapter-claude-code.discovery/load-config` loads bundled EDN
resources at namespace startup. Missing resources were already clear classpath
guards, but malformed EDN and non-map config payloads were still reported as
generic failures by the exception-as-data scanner. These are packaging/config
integrity guards, not recoverable runtime errors.

## Changes

- Mark malformed EDN diagnostics as invalid classpath config resources.
- Mark non-map config diagnostics as invalid classpath config resources.
- Add `:classpath/resource` and `:config/error` data to those failures.

## Validation

```bash
clojure -M:dev:test -e "(require 'ai.miniforge.adapter-claude-code.interface-test 'clojure.test) (clojure.test/run-tests 'ai.miniforge.adapter-claude-code.interface-test)"
```

```bash
clojure -M:dev:test -e '(require (quote [ai.miniforge.compliance-scanner.interface :as scanner])) (let [r (scanner/scan-exceptions-as-data ".") cleanup (filter #(= :cleanup-needed (:classification %)) (:violations r))] (println :cleanup-needed (count cleanup)))'
```
