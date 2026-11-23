;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-admission
  "Admit only a verified Job/Pod incarnation while preserving cancellation fences."
  (:require [ai.miniforge.dag-executor.protocols.executor :as executor]
            [ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-observation :as observation]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} interrupted []
  (result/err :pod-interrupted (messages/t :kubernetes/pod-interrupted)))

(defn- ^{:stratum 0} cache-environment!
  [cache {:keys [job-name task-id workdir options]} receipt pod]
  (let [pod-name (:pod-name pod)
        metadata (merge receipt pod)
        configuration (assoc options :metadata metadata)]
    (swap! cache assoc job-name pod-name)
    (result/ok (executor/create-environment-record
                job-name :kubernetes task-id workdir configuration))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} retain-if-live! [cache context receipt pod]
  (if (.isInterrupted (Thread/currentThread))
    (interrupted)
    (cache-environment! cache context receipt pod)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} retain!
  [cache context executable timeout-ms receipt candidate]
  (if (.isInterrupted (Thread/currentThread))
    (interrupted)
    (result/and-then (observation/verify! executable receipt timeout-ms candidate)
                     (partial retain-if-live! cache context receipt))))

(comment (interrupted))
