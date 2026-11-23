;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-load-quiescence-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-container-termination :as termination]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} PodState
  [:map
   ["spec" [:map ["schedulingGates" {:optional true} [:vector :map]]
            ["nodeName" {:optional true} :string] ["restartPolicy" {:optional true} :string]
            ["containers" [:vector {:min 1} :map]]
            ["initContainers" {:optional true} [:vector :map]]
            ["ephemeralContainers" {:optional true} [:vector :map]]]]
   ["status" {:optional true}
    [:map ["phase" {:optional true} :string]
     ["containerStatuses" {:optional true} [:vector :map]]
     ["initContainerStatuses" {:optional true} [:vector :map]]
     ["ephemeralContainerStatuses" {:optional true} [:vector :map]]]]])

(def ^{:stratum 0} Termination
  [:and
   [:map ["exitCode" :int] ["reason" [:enum "Completed" "Error" "OOMKilled"]]
    ["startedAt" :string] ["finishedAt" :string] ["containerID" schema/NonBlankString]]
   [:fn termination/ordered-times?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} WorkerStatus
  [:map ["name" [:= "load"]] ["restartCount" [:= 0]]
   ["containerID" schema/NonBlankString]
   ["state" [:map {:closed true} ["terminated" Termination]]]])

(comment ::synthetic-unknown-termination-without-real-container-identity-and-times-is-not-proof)
