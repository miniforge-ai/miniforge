;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-persistence-support
  (:require [ai.miniforge.workflow.context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} phase-result [status] {:status status})

(defn- ^{:stratum 0} phase [name] {:phase name})

(defn- ^{:stratum 0} gate-failure []
  {:gate :stale-references
   :errors [{:message "stale" :timestamp (java.time.Instant/parse "2026-08-29T00:00:00Z")}]})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} results [statuses] (update-vals statuses phase-result))

(defn- ^{:stratum 1} definition [phases]
  (let [pipeline (mapv phase phases)]
    {:workflow/id :checkpoint-persistence :workflow/version "1.0.0" :workflow/pipeline pipeline}))

(defn ^{:stratum 1} gate-result [decision]
  (let [status (get {:deny :failed :allow :completed} decision)
        result {:status status :phase/decision-envelope {:envelope/decision decision}}]
    (cond-> result (= :deny decision) (assoc :phase/gate-failures [(gate-failure)]))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execution [root id phases phase-results]
  (-> (context/create-context (definition phases) {} {:checkpoint/root root})
      (assoc :execution/id id :execution/phase-results phase-results)))

(comment ::checkpoint-persistence-fixtures)
