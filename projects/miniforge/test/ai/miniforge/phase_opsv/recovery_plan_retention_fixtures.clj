;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.recovery-plan-retention-fixtures
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.evidence-bundle.interface :as evidence]
            [ai.miniforge.phase-opsv.artifact-model :as records]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.recovery-plan-admission-fixtures :as admission]
            [ai.miniforge.phase-opsv.recovery-plan-material-schema :as schema]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} retain [{:keys [ctx input]}]
  (phase/retain-kubernetes-recovery-plan! ctx input))

(defn ^{:stratum 0} read-plan [{:keys [ctx input plan-id]}]
  (phase/read-retained-kubernetes-recovery-plan! ctx input plan-id))

(defn ^{:stratum 0} published [{:keys [ctx plan-id]}]
  (artifact/read-published (get-in ctx [:execution/opts :opsv/artifact-directory]) plan-id))

(defn ^{:stratum 0} record [{:keys [ctx]} content descriptor]
  (records/record ctx {schema/content-key content} descriptor))

(defn ^{:stratum 0} publish! [{:keys [ctx]} value]
  (let [stored (artifact/publish! (get-in ctx [:execution/opts :opsv/artifact-directory]) value)
        id (:artifact/id stored)
        bundle (get-in ctx [:execution/input :opsv/evidence-bundle-id])
        assembly (evidence/accumulate-opsv-evidence! (:opsv/evidence-assembly-store ctx) bundle {:opsv/artifact-refs [id]})]
    (is (not (anomaly/anomaly? stored)))
    (is (contains? (:opsv/artifact-refs assembly) id))
    id))

(def ^{:stratum 0} with-input admission/with-input)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} with-retained [f]
  (admission/with-input
    (fn [fixture]
      (let [output (retain fixture)
            id (:recovery/artifact-ref output)]
        (is (not (anomaly/anomaly? output)))
        (is (uuid? id))
        (f (assoc fixture :retained-output output :plan-id id))))))

(comment ::actual-independent-admission-publication-and-acknowledgement-no-provider-effects)
