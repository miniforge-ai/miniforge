;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.canonical-validation
  "Validate portable N6 structure and declared content integrity, not authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.schema :as schema]
            [ai.miniforge.evidence-bundle.schema.domain :as domain]
            [ai.miniforge.evidence-bundle.schema.opsv :as opsv]
            [ai.miniforge.evidence-bundle.schema.validation :as validation]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} result [errors]
  {:valid? (empty? errors) :errors (vec errors)})

(defn- ^{:stratum 0} structural-errors [bundle]
  (let [checks (:evidence/policy-checks bundle)
        reports (into [(validation/validate-schema schema/evidence-bundle-schema bundle)
                       (validation/validate-schema domain/intent-schema (:evidence/intent bundle))
                       (validation/validate-schema domain/outcome-schema (:evidence/outcome bundle))]
                      (map (partial validation/validate-schema domain/policy-check-schema)
                           (when (vector? checks) checks)))]
    (cond-> (vec (mapcat :errors reports))
      (and (contains? bundle :evidence/opsv) (not (m/validate opsv/OpsvEvidence (:evidence/opsv bundle))))
      (conj {:code :invalid-opsv-evidence}))))

(defn- ^{:stratum 0} hash-errors [bundle]
  (when (and (contains? bundle :evidence/content-hash)
             (not= (:evidence/content-hash bundle) (hash/content-hash (dissoc bundle :evidence/content-hash))))
    [{:code :content-hash-mismatch}]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} validate-portable [bundle]
  (let [portable (artifact/content-digest bundle)]
    (cond
      (anomaly/anomaly? portable) (result [{:code :nonportable-evidence :anomaly portable}])
      (not (map? bundle)) (result [{:code :invalid-bundle}])
      :else (result (into (structural-errors bundle) (hash-errors bundle))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} validate-with-exception-handling [bundle]
  (try (validate-portable bundle)
       (catch InterruptedException interrupted
         (.interrupt (Thread/currentThread))
         (throw interrupted))
       (catch Exception _ (result [{:code :invalid-bundle}]))))

(comment
  (validate-with-exception-handling {}))
