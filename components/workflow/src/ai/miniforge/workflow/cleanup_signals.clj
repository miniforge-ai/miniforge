;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.cleanup-signals
  "Value construction for completion observers."
  (:require [ai.miniforge.phase.interface :as phase]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} completion [output-ctx]
  (let [signal-type (if (phase/succeeded? output-ctx) :workflow-complete :workflow-failed)]
    {:signal/type signal-type
     :workflow-id (:execution/id output-ctx)
     :phase-results (:execution/phase-results output-ctx)
     :metrics (:execution/metrics output-ctx)
     :timestamp (java.time.Instant/now)}))

(defn- ^{:stratum 0} exception-message [exception]
  (cond
    (instance? Throwable exception) (ex-message exception)
    (map? exception) (:message exception)
    (some? exception) (str exception)
    :else nil))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} failure [workflow exception]
  (let [error (exception-message exception)]
    {:signal/type :workflow-failed
     :workflow-id (:workflow/id workflow)
     :error error
     :timestamp (java.time.Instant/now)}))

(comment ::completion-observer-values)
