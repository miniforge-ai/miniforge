;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.chain-event-payload
  "Lifecycle payload vocabulary shared by current and legacy publication adapters."
  (:require [ai.miniforge.failure-classifier.interface :as failure]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} identified [record fields]
  (let [identity (select-keys record [:chain/run-id :chain/definition-id :chain/definition-version])]
    (merge fields identity)))

(defn ^{:stratum 0} step-fields [opts step index fields]
  (merge fields {:step/id (:step/id step)
                 :step/index index
                 :step/workflow-id (:step/workflow-id step)
                 :workflow/id (:workflow-id opts)}))

(defn- ^{:stratum 0} classified [payload]
  (if (contains? payload :chain/error)
    (assoc payload :failure/class (failure/classify {:error/message (:chain/error payload)}))
    payload))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} current [record fields]
  (-> (identified record fields) classified))

(comment
  (identified {} {}))
