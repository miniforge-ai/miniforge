;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.opsv-draft-spec
  "Sequence-free OPSV admission derived from the authoritative N3 event schemas."
  (:require [ai.miniforge.event-stream.schema.opsv :as opsv]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private schemas
  [opsv/ExperimentPlanned opsv/ExperimentStarted opsv/LoadStep opsv/GuardrailAbort
   opsv/ConvergenceIteration opsv/PolicyProposed opsv/VerificationResult
   opsv/ActuationEmitted opsv/ActuationDisposition opsv/DriftDetected])

(defn- ^{:stratum 0} position-entry? [entry]
  (and (vector? entry) (= :event/sequence-number (first entry))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} without-position [schema]
  ;; Only envelope maps change. Nested payloads and cross-field predicates stay intact.
  (case (first schema)
    :map (into [:map] (remove position-entry?) (rest schema))
    :and (into [:and] (map without-position) (rest schema))
    schema))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Draft
  [:and [:map [:event/version [:= "1.0.0"]] [:scope/type [:= :workflow]]]
   (into [:or] (map without-position) schemas)])

(comment
  Draft)
