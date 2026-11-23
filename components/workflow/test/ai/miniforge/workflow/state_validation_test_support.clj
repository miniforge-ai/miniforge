;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-validation-test-support
  "Deliberately malformed topology fixtures for validation integration tests.")

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} default-entry? [entry]
  (and (map? entry) (not (contains? entry :guard))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} strip-state-default [config]
  (let [entries (get-in config [:on :phase/fail])]
    (if (sequential? entries)
      (assoc-in config [:on :phase/fail] (vec (remove default-entry? entries)))
      config)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} without-defaults [builder workflow]
  (update (builder workflow) :states update-vals strip-state-default))

(comment ::malformed-topology-fixture)
