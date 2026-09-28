;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.finalization-config
  "Validate host-supplied evidence intent before any phase invokes an adapter."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.messages :as msg]
            [ai.miniforge.phase-opsv.runtime-context :as context]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} enabled? [ctx]
  (contains? (:execution/opts ctx) :opsv/evidence-base))

(defn ^{:stratum 0} base-bundle [ctx]
  (assoc (select-keys (let [base (get-in ctx [:execution/opts :opsv/evidence-base])]
                       (when (map? base) base))
                      [:evidence-bundle/workflow-id :evidence-bundle/created-at
                       :evidence-bundle/version :evidence/intent])
         :evidence-bundle/id (get-in ctx [:execution/input :opsv/evidence-bundle-id])
         :evidence/outcome {:outcome/success true}))

(defn- ^{:stratum 0} assembly-status [ctx]
  (when-let [store (:opsv/evidence-assembly-store ctx)]
    (:opsv.assembly/status
     (evidence/get-opsv-assembly store (get-in ctx [:execution/input :opsv/evidence-bundle-id])))))

(defn- ^{:stratum 0} directory-readable? [ctx]
  (not (anomaly/any-anomaly?
        (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory])
                                 (get-in ctx [:execution/input :opsv/evidence-bundle-id])))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} valid-base? [ctx]
  (let [base (base-bundle ctx)]
    (and (map? (get-in ctx [:execution/opts :opsv/evidence-base]))
               (string? (get-in ctx [:execution/opts :opsv/artifact-directory]))
               (uuid? (:evidence-bundle/id base))
               (uuid? (context/workflow-id ctx))
               (= (context/workflow-id ctx) (:evidence-bundle/workflow-id base))
               (inst? (:evidence-bundle/created-at base))
               (string? (:evidence-bundle/version base))
         (:valid? (evidence/validate-bundle base))
         (directory-readable? ctx))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} validate-context [ctx]
  (cond
    (= :finalized (assembly-status ctx))
    (anomaly/anomaly :invalid-input (msg/t :evidence/assembly-finalized) {})
    (and (enabled? ctx) (not (valid-base? ctx)))
    (anomaly/anomaly :invalid-input (msg/t :evidence/invalid-finalization-config) {})
    :else ctx))
