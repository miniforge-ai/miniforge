;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.state-structure-scan
  "Inspect guarded failure arrays before resolving their keyword references.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} redirect-action :redirect/inc-count)

(def ^{:stratum 0} redirect-budget-guard :budget/redirects-spent?)

(defn- ^{:stratum 0} guarded-entry [[state-id config]]
  (let [entries (get-in config [:on :phase/fail])]
    (when (sequential? entries) [state-id (vec entries)])))

(defn ^{:stratum 0} entry-default? [entry]
  (and (map? entry) (not (contains? entry :guard))))

(defn- ^{:stratum 0} action-index [action index entry]
  (when (and (map? entry) (some #{action} (get entry :actions []))) index))

(defn- ^{:stratum 0} guard-index [guard index entry]
  (when (and (map? entry) (= guard (:guard entry))) index))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} guarded-fail-states [states]
  (keep guarded-entry states))

(defn ^{:stratum 1} redirect-indices [entries]
  (vec (keep-indexed (partial action-index redirect-action) entries)))

(defn ^{:stratum 1} budget-index [entries]
  (first (keep-indexed (partial guard-index redirect-budget-guard) entries)))

(comment ::guarded-transition-inspection)
