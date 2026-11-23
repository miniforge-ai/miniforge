;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-pod
  (:require [ai.miniforge.phase-opsv.governed-experiment-schema :as bounds]
            [ai.miniforge.phase-opsv.kubernetes-load-container :as worker]
            [ai.miniforge.phase-opsv.kubernetes-read-plan :as paths]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} api-version "A bare Pod has no controller recreating a finished or failed worker." "v1")

(defn ^{:stratum 0} name-of [input] (str "mf-opsv-load-" (get-in input [:limits :effect/id])))

(defn- ^{:stratum 0} labels [input]
  (let [workflow (str (get-in input [:declaration :workflow/id]))
        attempt (str (get-in input [:request :verification/id]))
        effect (str (get-in input [:limits :effect/id]))]
    {"miniforge.ai/workflow-run" workflow "miniforge.ai/verification" attempt "miniforge.ai/effect" effect}))

(defn- ^{:stratum 0} pod-spec [input]
  (let [containers [(worker/container input)]
        deadline (quot (get-in input [:limits :opsv/duration-ms]) bounds/millis-per-second)]
    {"restartPolicy" "Never" "automountServiceAccountToken" false "enableServiceLinks" false
     "nodeSelector" {"kubernetes.io/os" "linux"}
     "activeDeadlineSeconds" deadline "terminationGracePeriodSeconds" 0
     "schedulingGates" worker/scheduling-gates "securityContext" worker/pod-security "containers" containers}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} resource-reference [input]
  (let [namespace (get-in input [:declaration :runtime/target :namespace])
        name (name-of input)]
    {:api-version api-version :kind "Pod" :namespace namespace :name name :uid nil}))

(defn- ^{:stratum 1} body [input]
  (let [name (name-of input)
        namespace (get-in input [:declaration :runtime/target :namespace])
        labels (labels input)
        pod (pod-spec input)
        metadata {"name" name "namespace" namespace "labels" labels}]
    {"apiVersion" api-version "kind" "Pod" "metadata" metadata "spec" pod}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} request [input]
  (let [namespace (get-in input [:declaration :runtime/target :namespace])
        path (paths/pods-path namespace)
        body (body input)]
    {:method :post :path path :media-type "application/json" :body body}))

(comment ::creation-stays-unscheduled-labels-and-deadlines-do-not-prove-ownership-or-termination)
