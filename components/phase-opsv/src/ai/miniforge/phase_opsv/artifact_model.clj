;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.artifact-model
  "Pure phase-material selection and content-bound artifact identity."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.protected-material :as protected]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} without-attempt-receipts [output]
  (let [cleared (dissoc output :opsv/phase-produced-artifact-refs)]
    (if (contains? (:anomaly/data cleared) :opsv/phase-output)
      (update-in cleared [:anomaly/data :opsv/phase-output] dissoc :opsv/phase-produced-artifact-refs)
      cleared)))

(defn ^{:stratum 0} begin-publication [output]
  (assoc output :opsv/phase-produced-artifact-refs []))

(def ^{:stratum 0} phase-materials
  {:opsv/discover [[:experiment-pack :manifest :opsv/experiment-pack]]
   :opsv/execute [[:metric-snapshot :telemetry :opsv/ramp-steps]]
   :opsv/converge [[:convergence :review :opsv/convergence-result]]
   :opsv/synthesize [[:policy :manifest :opsv/operational-policy]]
   :opsv/verify [[:policy :manifest :opsv/operational-policy]
                 [:verification :review :opsv/verification-result]
                 [:verification-measurements :telemetry :opsv/verification-run]]
   :opsv/actuate [[:actuation :review :opsv/actuation-record]]})

(defn ^{:stratum 0} record [ctx output [kind type content-key]]
  (anomaly/let-ok [prepared (evidence/prepare-artifact-content (get output content-key))]
    (protected/record ctx kind type (:artifact/content prepared) (:artifact/metadata prepared))))

(def ^{:stratum 0} snapshot-kinds [:metric-snapshot :verification-measurements])

(def ^{:stratum 0} verification-environment-material
  [:verification-environment :opsv-verification-report :opsv/verification-environment])

(defn- ^{:stratum 0} append-reference [references id]
  (vec (distinct (conj references id))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} snapshot-references [output]
  (vec (keep (get output :opsv/phase-artifact-ids {}) snapshot-kinds)))

(defn- ^{:stratum 1} attach-reference [output id kind]
  (-> output
      (assoc-in [:opsv/phase-artifact-ids kind] id)
      (update :opsv/phase-produced-artifact-refs append-reference id)
      (update :opsv/artifact-refs append-reference id)))

(defn ^{:stratum 1} materials [phase-key output]
  (cond-> (get phase-materials phase-key [])
                    (and (= :opsv/verify phase-key) (contains? output :opsv/verification-environment))
                    (conj verification-environment-material)
                    (and (= :opsv/actuate phase-key) (contains? output :opsv/effect-transactions))
                    (conj [:effect-transactions :review :opsv/effect-transactions])
                    (and (= :opsv/actuate phase-key) (contains? output :opsv/phase-failure))
                    (conj [:actuation-failure :review :opsv/phase-failure])))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} records [ctx phase-key output]
  (mapv (partial record ctx output) (materials phase-key output)))

(defn ^{:stratum 2} confirmed-output [output]
  (if (contains? output :opsv/metric-snapshot-artifact-refs)
    (assoc output :opsv/metric-snapshot-artifact-refs (snapshot-references output))
    output))

(defn ^{:stratum 2} attach [output artifact]
  (let [id (:artifact/id artifact)
        kind (get-in artifact [:artifact/metadata :opsv/material-kind])
        attached (attach-reference output id kind)]
    (if (some #{kind} snapshot-kinds)
      (assoc attached :opsv/metric-snapshot-artifact-refs (snapshot-references attached))
      attached)))

(comment
  (records {} :opsv/plan {}))
