<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Repo DAG Schema Alias Anomalies

## Overview

Convert `repo-dag.core/validate-schema` from a throwing compatibility helper
into a stable alias for `validate-schema-anomaly`.

## Motivation

Schema rejection is validation data. The repo-dag component already exposes an
anomaly-returning helper; the legacy alias should preserve that data instead of
rethrowing it as `ex-info`.

## Testing

- `clojure -M:dev:test -e '(require (quote [clojure.test :as t]) (quote
  ai.miniforge.repo-dag.anomaly.validate-schema-test)) (let [r (t/run-tests (quote
  ai.miniforge.repo-dag.anomaly.validate-schema-test))] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))'`
- `bb pre-commit`
