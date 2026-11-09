;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.governed-replica-metadata-integration-test
  (:require [ai.miniforge.execution-grant.interface :as grant]
            [ai.miniforge.phase-opsv.governed-replica-proposal-fixtures :as fixtures]
            [ai.miniforge.phase-opsv.governed-replica-metadata-fixtures :as metadata]
            [ai.miniforge.phase-opsv.interface :as phase]
            [ai.miniforge.phase-opsv.kubernetes-replica-request-fixtures :as native]
            [clojure.test :refer [deftest is]]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} check-original [{:keys [ctx input native value]}]
  (let [raw-path [:environment/raw-observations :kubernetes/verification :kubernetes/source :kubernetes/inventory]
        original (get-in native (into [:baseline] raw-path))
        retained (get-in value (into [:artifact/content :comparison/preparation] raw-path))]
    (doseq [resource [:kubernetes/namespace :kubernetes/deployment]
            field ["labels" "annotations"]]
      (is (map? (get-in retained [resource "metadata" field])))
      (is (not= (get-in original [resource "metadata" field]) (get-in retained [resource "metadata" field]))))
    (doseq [[operation expected] [[:trial-apply native/trial-count] [:restore-baseline native/original-count]]]
      (let [prepared (assoc input :parameters (native/parameters operation))
            proposal (phase/prepare-governed-kubernetes-replica-proposal! ctx prepared)]
        (is (m/validate grant/OpsvExperimentScope proposal))
        (is (= expected (get (last (get-in proposal [:kubernetes/request :body])) "value")))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} published-labeled-and-annotated-originals-support-trial-and-restoration-test
  (fixtures/with-input metadata/input check-original))
