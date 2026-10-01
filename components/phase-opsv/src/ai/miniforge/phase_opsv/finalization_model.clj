;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-model
  "Project completed runtime material into the canonical N6 OPSV section."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.phase-opsv.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} failure [output reason]
  (anomaly/anomaly :fault (msg/t :evidence/finalization-failed)
                   {:opsv/phase-output output :opsv/finalization-reason reason}))

(defn ^{:stratum 0} policy-proposal [output]
  (let [policy (:opsv/operational-policy output)]
    {:policy-hash (:opsv/policy-hash output)
     :confidence (get-in output [:opsv/verification-result :confidence])
     :scaling (:operational-policy/scaling policy)
     :resources (:operational-policy/resources policy)
     :artifact-id (get-in output [:opsv/phase-artifact-ids :policy])}))

(defn ^{:stratum 0} bundle-artifact [bundle]
  (artifact/build-artifact
   {:id (:evidence-bundle/id bundle)
    :type :review :version "1.0.0" :content bundle
    :metadata {:workflow/id (:evidence-bundle/workflow-id bundle)
                       :opsv/evidence-bundle-id (:evidence-bundle/id bundle)
                       :opsv/material-kind :evidence-bundle
               :content/hash (:evidence/content-hash bundle)}}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} evidence-section [assembly output]
  (merge (into {} (map (fn [key] [key (vec (get assembly key))])
                       [:opsv/event-refs :opsv/artifact-refs :opsv/grant-refs]))
         {:opsv/experiment-pack-hash (:opsv/experiment-pack-hash output)
          :opsv/experiment-pack-id (get-in output [:opsv/experiment-pack :experiment-pack/id])
          :opsv/experiment-pack-artifact-id (get-in output [:opsv/phase-artifact-ids :experiment-pack])
          :opsv/environment-fingerprint (:opsv/environment-fingerprint output)
          :opsv/risk-score (:opsv/risk-result output)
          :opsv/convergence-iterations (get-in output [:opsv/convergence-result :iterations])
          :opsv/policy-proposals [(policy-proposal output)]
          :opsv/verification (:opsv/verification-result output)
          :opsv/actuation (:opsv/actuation-record output)
          :opsv/metric-query-artifact-refs []
          :opsv/metric-snapshot-artifact-refs (:opsv/metric-snapshot-artifact-refs output)
          :opsv/diff-artifact-refs []}))
