<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Repo DAG Edge Alias Anomalies

## Overview

Convert repo-dag `add-edge` and `remove-edge` aliases from throwing wrappers to
data-returning anomaly aliases.

## Motivation

Edge validation failures already have structured anomaly results. The stable
aliases should preserve that data contract instead of rethrowing it inside the
component.

## Testing

- `clojure -M:dev:test -e '(require (quote [clojure.test :as t]) (quote ai.miniforge.repo-dag.dag-crud-test) (quote
  ai.miniforge.repo-dag.dag-topology-test) (quote ai.miniforge.repo-dag.anomaly.add-edge-test) (quote
  ai.miniforge.repo-dag.anomaly.remove-edge-test)) (let [r (t/run-tests (quote ai.miniforge.repo-dag.dag-crud-test)
  (quote ai.miniforge.repo-dag.dag-topology-test) (quote ai.miniforge.repo-dag.anomaly.add-edge-test) (quote
  ai.miniforge.repo-dag.anomaly.remove-edge-test))] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))'`
- `bb pre-commit`
