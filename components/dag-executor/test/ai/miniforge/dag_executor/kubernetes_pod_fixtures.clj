;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-pod-fixtures
  "Native producer-shaped Pod replies derived from the actual fixture Job response."
  (:require [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as creation]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} controller [{:keys [job-name job-uid]}]
  {"apiVersion" "batch/v1"
   "kind" "Job"
   "name" job-name
   "uid" job-uid
   "controller" true})

(defn- ^{:stratum 0} receipt [created]
  (let [{:keys [name namespace uid]} (:metadata created)]
    {:job-name name
     :namespace namespace
     :job-uid uid}))

(defn ^{:stratum 0} reply [observed]
  (creation/provider-reply 0 (json/generate-string observed)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pod [{:keys [namespace] :as job} pod-name]
  (let [owner (controller job)]
    {"apiVersion" "v1"
     "kind" "Pod"
     "metadata" {"name" pod-name
                 "namespace" namespace
                 "uid" "observed-pod-uid"
                 "resourceVersion" "observed-pod-version"
                 "ownerReferences" [owner]}
     "status" {"phase" "Running"}}))

(defn ^{:stratum 1} capture-created! [state submit! & arguments]
  (let [reply (apply submit! arguments)
        created (json/parse-string-strict (:out reply) true)]
    (reset! state (receipt created))
    reply))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-pod! [state _until arguments]
  (let [observed (pod @state (last arguments))]
    (reply observed)))

(comment (pod {:job-name "example"
               :job-uid "example-job"
               :namespace "example"} "example-pod"))
