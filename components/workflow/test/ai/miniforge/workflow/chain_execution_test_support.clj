;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-execution-test-support
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.chain :as chain]
            [ai.miniforge.workflow.loader :as loader]
            [ai.miniforge.workflow.runner :as runner]
            [slingshot.slingshot :refer [throw+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} step [id]
  {:step/id id :step/workflow-id id :step/input-bindings {}})

(defn ^{:stratum 0} missing-workflow [& _]
  (response/throw-anomaly! :anomalies/not-found "Missing workflow" {}))

(defn ^{:stratum 0} loaded-workflow [& _]
  {:workflow {:workflow/id :fixture}})

(defn- ^{:stratum 0} record-result [calls result & _]
  (swap! calls inc)
  (if (instance? Exception result) (throw+ result) result))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} definition []
  (let [steps [(step :first) (step :dependent)]]
    {:chain/id :outcome-test :chain/version "1.0.0" :chain/steps steps}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} observed-run [load-workflow result]
  (let [calls (atom 0)
        stream (events/create-event-stream {:sinks []})]
    (with-redefs [loader/load-workflow load-workflow
                  runner/run-pipeline (partial record-result calls result)]
      (let [outcome (chain/run-chain (definition) {} {:event-stream stream})
            event-types (mapv :event/type (:events @stream))]
        {:outcome outcome :calls @calls :events event-types}))))

(comment
  (definition))
