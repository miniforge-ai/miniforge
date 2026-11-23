;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.dag-executor.protocols.impl.kubernetes-pod-identity
  "Pure projection of native Pod evidence into an exact, controller-bound identity."
  (:require [ai.miniforge.dag-executor.protocols.impl.kubernetes-job-creation :as creation]
            [ai.miniforge.kubernetes-target.interface :as target]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} parent [{:keys [job-name namespace job-uid]}]
  {"apiVersion" creation/api-version
   "kind" creation/resource-kind
   "metadata" {"name" job-name
               "namespace" namespace
               "uid" job-uid}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} confirmed [job pod-name observed]
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
      {:pod-name pod-name
       :pod-uid uid
       :pod-observed-resource-version version})))

(comment (confirmed {} "example" {}))
