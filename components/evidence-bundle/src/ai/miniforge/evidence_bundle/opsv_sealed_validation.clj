;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-sealed-validation
  "Read-only validation of an existing seal against its retained assembly references."
  (:require [ai.miniforge.evidence-bundle.canonical-validation :as canonical]
            [ai.miniforge.evidence-bundle.opsv-finalization-references :as references]
            [ai.miniforge.evidence-bundle.schema.opsv :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} correlated? [record bundle]
  (and (= :finalized (:opsv.assembly/status record))
       (= (:evidence-bundle/id record) (:evidence-bundle/id bundle))
       (= (:evidence-bundle/workflow-id record) (:evidence-bundle/workflow-id bundle))
       (string? (:evidence/content-hash bundle))
       (m/validate schema/OpsvEvidence (:evidence/opsv bundle))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} valid? [record bundle available-ids]
  (and (:valid? (canonical/validate-with-exception-handling bundle))
       (correlated? record bundle)
       (empty? (references/errors record (:evidence/opsv bundle) available-ids))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} valid-with-exception-handling? [record bundle available-ids]
  ;; No slingshot dependency; malformed restored input is a validation failure.
  (try (boolean (valid? record bundle available-ids))
       (catch InterruptedException interrupted
         (.interrupt (Thread/currentThread))
         (throw interrupted))
       (catch Exception _ false)))

(comment
  (valid-with-exception-handling? {} {} #{}))
