;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.publication-validation
  "Require a complete verified seal before presenting or exporting evidence."
  (:require [ai.miniforge.evidence-bundle.canonical-validation :as canonical]
            [ai.miniforge.evidence-bundle.publication-compliance :as compliance]
            [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reject [report code]
  (-> report (assoc :valid? false) (update :errors conj {:code code})))

(defn- ^{:stratum 0} compliant-with-exception-handling? [bundle]
  ;; This leaf component has no Slingshot dependency.
  (try (and (redaction/clean? bundle) (compliance/accurate-declarations? bundle))
       (catch InterruptedException interrupted
         (.interrupt (Thread/currentThread))
         (throw interrupted))
       (catch Exception _ false)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} validate [bundle]
  (let [report (canonical/validate-with-exception-handling bundle)]
    (cond
      (not (and (map? bundle) (contains? bundle :evidence/content-hash)))
      (reject report :unsealed-evidence)
      (not (:valid? report)) report
      (not (compliant-with-exception-handling? bundle)) (reject report :invalid-publication-compliance)
      :else report)))

(comment
  (validate {}))
