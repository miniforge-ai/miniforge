;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.environment-test-fixtures
  (:require [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acquire [workdir metadata _executor task-id _config]
  (dag/ok {:environment-id (str task-id) :workdir workdir :metadata metadata}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} executor-functions [workdir metadata]
  {:acquire-env! (partial acquire workdir metadata)
   :result-ok? dag/ok?
   :result-unwrap dag/unwrap})

(comment ::environment-port-fixtures)
