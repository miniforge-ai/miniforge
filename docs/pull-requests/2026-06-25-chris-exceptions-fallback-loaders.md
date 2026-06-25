# Refactor: Consolidate Fail-Open Config Loaders

## Overview

This PR removes duplicated classpath EDN config-resource parsing from agent and
semantic analyzer call sites by routing them through the shared
`ai.miniforge.config.interface/read-config-resource-or` helper. It also moves
the event-stream storage layout's required resource load onto the shared
`load-config-resource` boundary.

## Motivation

The standards remediation pass found repeated local implementations of the same
config-resource pattern:

```clojure
(try
  (let [url (io/resource path)
        parsed (when url (edn/read-string (slurp url)))]
    (if (map? parsed) parsed fallback))
  (catch Exception _ fallback))
```

Those helpers duplicated config parsing policy and made cancellation behavior
inconsistent across components. The shared config component already owns both
required-resource and fail-open resource loading, including UTF-8 reads and
interruption propagation.

## Changes in Detail

- `agent.meta-evaluator` now loads meta-eval tuning through
  `config/read-config-resource-or`.
- `agent.supervisory-bridge` now loads heartbeat defaults through
  `config/read-config-resource-or`.
- `semantic-analyzer.core` now loads analyzer limits through
  `config/read-config-resource-or`.
- `event-stream.storage-layout` now loads the required storage layout resource
  through `config/load-config-resource`, while preserving the local string-key
  validation.
- Added config component dependencies where newly required.

## Testing Plan

- Focused namespace/test run:
  `clojure -M:dev:test -e "(require ...)"`.
- Standards review:
  `bb review`.
- Full pre-commit before merge:
  `bb pre-commit`.

## Deployment Plan

No special deployment steps. The resource paths and fallback defaults are
unchanged.

## Related Issues/PRs

- Continues the exceptions-as-data/config-resource remediation waves after
  PRs #1271 and #1272.

## Checklist

- [x] Preserve fail-open behavior for optional config resources.
- [x] Preserve fail-fast behavior for required event-stream storage layout.
- [x] Confirm focused tests pass.
- [x] Confirm `bb review` decreases from 280 to 279 violations.
- [x] Run `bb pre-commit`.
