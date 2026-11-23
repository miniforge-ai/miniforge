;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-baseline-recovery
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.phase-opsv.environment-comparison-failure :as failure]
            [ai.miniforge.phase-opsv.verification-environment :as comparison]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} desired-count [observation]
  (get-in observation [:environment/raw-observations :kubernetes/verification :kubernetes/source
                       :kubernetes/inventory :kubernetes/deployment "spec" "replicas"]))

(defn- ^{:stratum 0} captured-failure [acquired result]
  (cond-> (assoc-in result [:anomaly/data :environment/raw-observations]
                    {:kubernetes/verification {:kubernetes/source (:preparation/capture acquired)}})
    (= :conflict (:anomaly/type result))
    (assoc-in [:anomaly/data :comparison/reason] :native-projection-conflict)))

(defn ^{:stratum 0} report [kind input admitted original observed comparison]
  (let [load (get-in input [:recovery/input :load])
        pin (get-in load [:request :comparison/pin])
        passed? (not (anomaly/anomaly? comparison))]
    {:verification/kind kind
     :verification/passed? passed?
     :recovery/artifact-ref (:recovery/artifact-ref input)
     :recovery/hash (:recovery/hash admitted)
     :comparison/artifact-ref (:comparison/artifact-ref load)
     :comparison/pin pin
     :comparison/original original
     :comparison/observed observed
     :comparison/result comparison}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} observation [acquired]
  (let [result (:preparation/result acquired)]
    (if (anomaly/anomaly? result) (captured-failure acquired result) result)))

(defn ^{:stratum 1} compare-original [pin original observed]
  ;; Population bounds allow replacement/churn, not leaving the candidate's desired count applied.
  (cond
    (anomaly/anomaly? observed) observed
    (not= (desired-count original) (desired-count observed))
    (failure/mismatch :baseline-desired-replicas-mismatch (desired-count original) (desired-count observed))
    :else (comparison/compare-observation pin (:comparison/hash pin) :rollback observed)))

(comment
  (report :baseline-recovery {} {} {} {} (anomaly/anomaly :conflict "example mismatch" {})))
