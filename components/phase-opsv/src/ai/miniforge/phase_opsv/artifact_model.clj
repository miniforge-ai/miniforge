;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-model
  "Pure phase-material selection and content-bound artifact identity."
  (:require [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.phase-opsv.runtime-context :as context])
  (:import [java.nio.charset StandardCharsets]
           [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} phase-materials
  {:opsv/discover [[:experiment-pack :manifest :opsv/experiment-pack]]
   :opsv/execute [[:metric-snapshot :telemetry :opsv/ramp-steps]]
   :opsv/converge [[:convergence :review :opsv/convergence-result]]
   :opsv/synthesize [[:policy :manifest :opsv/operational-policy]]
   :opsv/verify [[:policy :manifest :opsv/operational-policy]
                 [:verification :review :opsv/verification-result]]
   :opsv/actuate [[:actuation :review :opsv/actuation-record]]})

(defn ^{:stratum 0} record [ctx output [kind type content-key]]
  (let [workflow-id (context/workflow-id ctx)
        bundle-id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        content (get output content-key)
        digest (hash/content-hash [workflow-id bundle-id kind content])
        id (UUID/nameUUIDFromBytes (.getBytes ^String digest StandardCharsets/UTF_8))
        metadata {:workflow/id workflow-id
                  :opsv/evidence-bundle-id bundle-id
                  :opsv/material-kind kind
                  :content/hash digest}]
    (artifact/build-artifact {:id id :type type :version "1.0.0" :content content :metadata metadata})))

(defn ^{:stratum 0} attach [output artifact]
  (let [id (:artifact/id artifact)
        kind (get-in artifact [:artifact/metadata :opsv/material-kind])]
    (-> output
        (assoc-in [:opsv/phase-artifact-ids kind] id)
        (update :opsv/artifact-refs #(vec (distinct (conj (or % []) id)))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} records [ctx phase-key output]
  (let [materials (cond-> (get phase-materials phase-key [])
                    (and (= :opsv/actuate phase-key) (contains? output :opsv/effect-transactions))
                    (conj [:effect-transactions :review :opsv/effect-transactions])
                    (and (= :opsv/actuate phase-key) (contains? output :opsv/phase-failure))
                    (conj [:actuation-failure :review :opsv/phase-failure]))]
    (mapv (partial record ctx output) materials)))

(comment
  (records {} :opsv/plan {}))
