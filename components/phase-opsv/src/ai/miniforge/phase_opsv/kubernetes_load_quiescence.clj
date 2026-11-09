;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-quiescence
  "Classify fresh owned observations, never execution permission or recovered baseline."
  (:require [ai.miniforge.phase-opsv.kubernetes-load-container :as worker]
            [ai.miniforge.phase-opsv.kubernetes-load-quiescence-schema :as schema]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} valid-worker-status? (m/validator schema/WorkerStatus))

(def ^{:stratum 0 :private true} valid-pod-state? (m/validator schema/PodState))

(def ^{:stratum 0 :private true} status-collections
  ["containerStatuses" "initContainerStatuses" "ephemeralContainerStatuses"])

(defn- ^{:stratum 0} single-nonrestarting-worker? [spec]
  (let [containers (get spec "containers")]
    (and (= "Never" (get spec "restartPolicy"))
         (= 1 (count containers))
         (= "load" (get (first containers) "name"))
         (nil? (get (first containers) "restartPolicy"))
         (empty? (get spec "initContainers"))
         (empty? (get spec "ephemeralContainers")))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} never-scheduled? [pod]
  (let [spec (get pod "spec")
        status (get pod "status")]
    (and (= worker/scheduling-gates (get spec "schedulingGates"))
         (nil? (get spec "nodeName"))
         (contains? #{nil "Pending"} (get status "phase"))
         (every? #(empty? (get status %)) status-collections))))

(defn- ^{:stratum 1} worker-terminated? [status]
  (let [containers (get status "containerStatuses")
        worker (first containers)]
    (and (contains? #{"Succeeded" "Failed"} (get status "phase"))
         (= 1 (count containers))
         (empty? (get status "initContainerStatuses"))
         (empty? (get status "ephemeralContainerStatuses"))
         (valid-worker-status? worker)
         (= (get worker "containerID") (get-in worker ["state" "terminated" "containerID"])))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} outcome [owned]
  (let [pod (:load/pod owned)]
    (cond
      (not (valid-pod-state? pod)) :unresolved
      (never-scheduled? pod) :not-started
      (and (single-nonrestarting-worker? (get pod "spec"))
           (worker-terminated? (get pod "status"))) :terminated
      :else :unresolved)))

(comment ::caller-must-first-establish-durable-creation-and-fresh-live-uid-through-ownership-boundary)
