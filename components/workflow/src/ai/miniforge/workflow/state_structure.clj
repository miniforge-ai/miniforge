;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-structure
  "Validate defaults and redirect accounting in unresolved transition arrays."
  (:require [ai.miniforge.workflow.state-structure-scan :as scan]
            [ai.miniforge.workflow.state-structure-messages :as diagnostic]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} missing-default [state entries]
  (when-not (some scan/entry-default? entries)
    (diagnostic/guarded-fail-missing-default-error state)))

(defn- ^{:stratum 0} multiple-redirects [state indices]
  (when (> (count indices) 1)
    (diagnostic/multiple-redirect-actions-error state indices)))

(defn- ^{:stratum 0} budget-error [state redirect-index budget-index]
  (cond
    (nil? redirect-index) nil
    (nil? budget-index) (diagnostic/budget-missing-error state redirect-index)
    (> budget-index redirect-index) (diagnostic/budget-misordered-error state redirect-index budget-index)
    :else nil))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} state-errors [[state entries]]
  (let [indices (scan/redirect-indices entries)
        budget (scan/budget-index entries)
        default-error (missing-default state entries)
        redirect-error (multiple-redirects state indices)
        budget-error (budget-error state (first indices) budget)]
    {:default default-error :redirect redirect-error :budget budget-error}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} structural-invariant-errors [states]
  (let [findings (mapv state-errors (scan/guarded-fail-states states))]
    ;; Preserve diagnostic ordering: defaults, repeated redirects, then budgets.
    (vec (concat (keep :default findings) (keep :redirect findings) (keep :budget findings)))))

(comment ::guarded-transition-invariants)
