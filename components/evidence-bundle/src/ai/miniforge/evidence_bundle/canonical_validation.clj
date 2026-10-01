;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.canonical-validation
  "Validate portable N6 structure and declared content integrity, not authority."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.content-hash.interface :as hash]
            [ai.miniforge.evidence-bundle.canonical-structure :as structure]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} result [errors]
  {:valid? (empty? errors)
   :errors (vec errors)})

(defn- ^{:stratum 0} hash-errors [bundle]
  (when (and (contains? bundle :evidence/content-hash)
             (not= (:evidence/content-hash bundle)
                   (hash/content-hash (dissoc bundle :evidence/content-hash :evidence/signature))))
    [{:code :content-hash-mismatch}]))

(defn- ^{:stratum 0} sealing-errors [bundle]
  (when (some #(contains? bundle %) [:evidence/content-hash :evidence/signature :evidence/sealed-at])
    (when-not (and (string? (:evidence/content-hash bundle))
                   (inst? (:evidence/sealed-at bundle))
                   (contains? bundle :compliance/sensitive-data)
                   (contains? bundle :compliance/pii-handling)
                   (inst? (:compliance/created-at bundle)))
      [{:code :incomplete-seal}])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} validate-portable [bundle]
  (let [portable (artifact/content-digest bundle)]
    (cond
      (anomaly/anomaly? portable) (result [{:code :nonportable-evidence :anomaly portable}])
      (not (map? bundle)) (result [{:code :invalid-bundle}])
      :else (result (concat (structure/errors bundle) (sealing-errors bundle) (hash-errors bundle))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} validate-with-exception-handling [bundle]
  (try (validate-portable bundle)
       (catch InterruptedException interrupted
         (.interrupt (Thread/currentThread))
         (throw interrupted))
       (catch Exception _ (result [{:code :invalid-bundle}]))))

(comment
  (validate-with-exception-handling {}))
