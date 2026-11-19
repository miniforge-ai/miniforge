;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-observation
  "Verify the observed Pod against the exact Job creation receipt before admission."
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-creation :as job-creation]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline :as deadline]
            [ai.miniforge.dag-executor.protocols.impl.runtime.deadline-command :as command]
            [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
            [ai.miniforge.dag-executor.result :as result]
            [ai.miniforge.kubernetes-target.interface :as target]
            [cheshire.core :as json]
            [malli.core :as m]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} parent [{:keys [job-name namespace job-uid]}]
  {"apiVersion" job-creation/api-version
   "kind" job-creation/resource-kind
   "metadata" {"name" job-name "namespace" namespace "uid" job-uid}})

(defn- ^{:stratum 0} unconfirmed []
  (let [interrupted? (.isInterrupted (Thread/currentThread))
        failure (result/err :pod-owner-unconfirmed (messages/t :kubernetes/pod-owner-unconfirmed))]
    (when interrupted? (.interrupt (Thread/currentThread)))
    failure))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} retain [job pod-name observed]
  (let [metadata (get observed "metadata")
        uid (get metadata "uid")
        version (get metadata "resourceVersion")]
    (when (and (= "v1" (get observed "apiVersion"))
               (= "Pod" (get observed "kind"))
               (= pod-name (get metadata "name"))
               (= "Running" (get-in observed ["status" "phase"]))
               (nil? (get metadata "deletionTimestamp"))
               (every? (partial m/validate target/IdentityText) [uid version])
               (target/owns? (parent job) observed))
      {:pod-name pod-name :pod-uid uid :pod-observed-resource-version version})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify! [executable job request-timeout-ms {:keys [pod-name]}]
  (if-not (every? (partial m/validate target/IdentityText)
                  [pod-name (:namespace job) (:job-name job) (:job-uid job)])
    (unconfirmed)
    (try+
      (let [until (deadline/after-ms request-timeout-ms)
            binary (if (nil? executable) "kubectl" executable)
            args [binary "get" "pod" "-n" (:namespace job) "-o" "json"
                  (str "--request-timeout=" request-timeout-ms "ms") "--" pod-name]
            {:keys [exit out]} (command/run! until args)
            observed (when (= 0 exit) (retain job pod-name (json/parse-string-strict out)))]
        (if (and observed (not (deadline/expired? until))) (result/ok observed) (unconfirmed)))
      (catch Exception e
        (when (instance? InterruptedException e)
          (.interrupt (Thread/currentThread)))
        (unconfirmed)))))
