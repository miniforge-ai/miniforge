;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-finalization-publication
  "Atomically publish against the validated assembly version; retry concurrent accumulation."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.opsv-diagnostics :as diagnostics]
            [ai.miniforge.evidence-bundle.publication-compliance :as compliance]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} finalize-current [expected bundle current]
  (if (= expected current)
    (assoc current :opsv.assembly/status :finalized :opsv.assembly/bundle bundle)
    current))

(defn ^{:stratum 0} sealed-bundle [candidate sealed-at]
  (let [prepared (compliance/prepare candidate)
        dated (assoc prepared :evidence/sealed-at sealed-at :compliance/created-at sealed-at)]
    (assoc dated :evidence/content-hash (hash/content-hash dated))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} seal-with-exception-handling [record candidate]
  ;; No slingshot dependency; scanning and redaction are the sealing boundary.
  (try
    (sealed-bundle candidate (java.time.Instant/now))
    (catch InterruptedException interrupted
      (.interrupt (Thread/currentThread))
      (throw interrupted))
    (catch Exception _
      (diagnostics/failure :anomalies/incorrect :finalization/invalid
                           (:evidence-bundle/id record) [{:code :sealing-failed}]))))

(defn ^{:stratum 1} publish-sealed! [store record bundle]
  (let [bundle-id (:evidence-bundle/id record)
        transition (partial finalize-current record bundle)
        [old-state new-state] (swap-vals! store update bundle-id transition)]
    (if (= record (get old-state bundle-id))
      (get-in new-state [bundle-id :opsv.assembly/bundle])
      ::retry)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} publish! [store record candidate]
  (let [bundle (seal-with-exception-handling record candidate)]
    (if (anomaly/any-anomaly? bundle) bundle (publish-sealed! store record bundle))))

(comment
  ::retry)
