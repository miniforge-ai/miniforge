<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Pipeline Output Schema Anomalies

## Overview

Convert pipeline-output connector schema validation from throwing anomaly
exceptions to returning anomaly data at the connector boundary.

## Motivation

Connector boundary validation is caller-visible data validation. Invalid output
configuration or manifest data should return structured anomaly maps rather than
raise exceptions that bypass normal connector result handling.

## Testing

- `clojure -M:dev:test -e '(require (quote [clojure.test :as t]) (quote
  ai.miniforge.connector-pipeline-output.impl-test) (quote
  ai.miniforge.connector-pipeline-output.anomaly.pipeline-output-anomaly-test)) (let [r (t/run-tests (quote
  ai.miniforge.connector-pipeline-output.impl-test) (quote
  ai.miniforge.connector-pipeline-output.anomaly.pipeline-output-anomaly-test))] (when (pos? (+ (:fail r) (:error r)))
  (System/exit 1)))'`
- `bb pre-commit`
