<!--
  Title: Miniforge.ai
  Author: Christopher Lester (christopher@miniforge.ai)
  Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.
-->

# Repo DAG CRUD Alias Anomalies

## Overview

Convert repo-dag `add-repo` and `remove-repo` aliases from throwing wrappers to
data-returning anomaly aliases.

## Motivation

Repo CRUD failures are already represented by repo-dag anomaly data. The stable
aliases should preserve that contract instead of rethrowing the anomaly at an
internal component boundary.

## Testing

- `clojure -M:dev:test -e '(require (quote [clojure.test :as t]) (quote ai.miniforge.repo-dag.dag-crud-test) (quote
  ai.miniforge.repo-dag.anomaly.add-repo-test) (quote ai.miniforge.repo-dag.anomaly.remove-repo-test)) (let [r
  (t/run-tests (quote ai.miniforge.repo-dag.dag-crud-test) (quote ai.miniforge.repo-dag.anomaly.add-repo-test) (quote
  ai.miniforge.repo-dag.anomaly.remove-repo-test))] (when (pos? (+ (:fail r) (:error r))) (System/exit 1)))'`
- `bb pre-commit`
