;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.boundary.chain-step
  "Convert failures from binding, legacy loading, and execution into child outcomes."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.chain-bindings :as bindings]
            [ai.miniforge.workflow.chain-outcome :as outcome]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} execute-loaded [loaded input opts]
  (cond
    (anomaly/any-anomaly? loaded) (outcome/failure loaded)
    (not (map? (:workflow loaded))) (outcome/failure loaded)
    :else (runner/run-pipeline (:workflow loaded) input opts)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute [step previous input opts]
  (try+
    (let [resolved (bindings/resolve-bindings (:step/input-bindings step) previous input)
          loaded (loader/load-workflow (:step/workflow-id step) :latest {:skip-validation? true})]
      (outcome/normalize (execute-loaded loaded resolved opts)))
    (catch map? error (outcome/failure error))
    (catch Exception error (outcome/failure {:message (.getMessage error)}))))

(comment
  ::child-outcome-boundary)
