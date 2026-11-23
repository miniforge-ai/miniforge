;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.kubernetes-pod-fixtures
  "Native producer-shaped Pod replies derived from the actual fixture Job response."
  (:require [ai.miniforge.dag-executor.kubernetes-creation-fixtures :as creation]
            [ai.miniforge.dag-executor.result :as result]
            [cheshire.core :as json]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} pod-name "mock-capsule-pod")

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

(defn- ^{:stratum 0} list-pod [observed]
  (creation/provider-reply 0 (get-in observed ["metadata" "name"])))

(defn- ^{:stratum 0} pod-phase [observed]
  (creation/provider-reply 0 (get-in observed ["status" "phase"])))

(defn- ^{:stratum 0} refuse-query! [& _] (throw (AssertionError.)))

(defn ^{:stratum 0} structured-query? [arguments]
  (boolean (some #{["-o" "json"]} (partition 2 1 arguments))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ready [& _]
  (result/ok {:pod-name pod-name
              :status :running}))

(def ^{:stratum 1 :private true} readers
  {["pods" false] list-pod
   ["pod" false] pod-phase
   ["pod" true] reply})

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

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} capture-created! [state submit! & arguments]
  (let [reply (apply submit! arguments)
        created (json/parse-string-strict (:out reply) true)
        observed (pod (receipt created) pod-name)]
    (reset! state observed)
    reply))

(defn ^{:stratum 2} read-pod! [state _until arguments]
  (let [kind (nth arguments 2 nil)
        structured? (structured-query? arguments)
        read! (get readers [kind structured?] refuse-query!)
        observed @state]
    (if observed (read! observed) (refuse-query!))))

(comment (pod {:job-name "example"
               :job-uid "example-job"
               :namespace "example"} "example-pod"))
