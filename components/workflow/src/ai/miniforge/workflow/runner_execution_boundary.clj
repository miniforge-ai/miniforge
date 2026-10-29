;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.runner-execution-boundary
  "Convert pipeline exceptions to failures while retaining cleanup signals."
  (:require [ai.miniforge.resource-cleanup.interface :as resources]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.runner-loop :as pipeline-loop]
            [ai.miniforge.workflow.runner-outcome :as outcome]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} failed [ctx error]
  (-> ctx (update :execution/errors conj error) context/transition-to-failed))

(defn- ^{:stratum 0} exception-message [error]
  (if (instance? Throwable error) (ex-message error) (str error)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stopped [ctx message exception-vol]
  (let [error (outcome/make-execution-error :dashboard-stop message)]
    (vreset! exception-vol error)
    (failed ctx error)))

(defn- ^{:stratum 1} crashed [ctx error throwable exception-vol]
  (when-let [fatal (resources/fatal-cause throwable)]
    (resources/propagate! fatal))
  (let [message (exception-message error)
        data (when (instance? Throwable error) (ex-data error))
        diagnostic (outcome/make-execution-error :pipeline-exception message {:data data})]
    (vreset! exception-vol (or throwable diagnostic))
    (failed ctx diagnostic)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-with-exception-handling
  [pipeline ctx callbacks control-state max-phases exception-vol]
  (try+
    (pipeline-loop/execute-pipeline-loop pipeline ctx callbacks control-state max-phases)
    #_{:clj-kondo/ignore [:unresolved-namespace :unresolved-symbol]}
    (catch [:anomaly/category :anomalies.dashboard/stop] {:keys [anomaly/message]}
      (stopped ctx message exception-vol))
    #_{:clj-kondo/ignore [:unresolved-symbol]}
    (catch Object error
      (crashed ctx error (:throwable &throw-context) exception-vol))))

(comment ::pipeline-exception-boundary)
