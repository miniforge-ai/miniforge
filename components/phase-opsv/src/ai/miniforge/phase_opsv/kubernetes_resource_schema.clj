;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-resource-schema
  "Read-side Kubernetes JSON shapes, retaining native string keys."
  (:require [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Metadata
  [:map
   ["name" schema/NonBlankString]
   ["uid" schema/NonBlankString]
   ["namespace" {:optional true} schema/NonBlankString]
   ["resourceVersion" {:optional true} schema/NonBlankString]
   ["labels" {:optional true} [:map-of :string :string]]
   ["annotations" {:optional true} [:map-of :string :string]]])

(def ^{:stratum 0} Container
  [:map ["name" schema/NonBlankString] ["image" schema/NonBlankString]])

(def ^{:stratum 0} ContainerStatus
  [:map
   ["name" schema/NonBlankString]
   ["imageID" [:re #"\A(?:[a-z][a-z0-9+.-]*://)?(?:[A-Za-z0-9._:/-]+@)?sha256:[0-9a-f]{64}\z"]]])

(def ^{:stratum 0} NodeInfo
  [:map
   ["architecture" schema/NonBlankString]
   ["operatingSystem" schema/NonBlankString]
   ["kernelVersion" schema/NonBlankString]
   ["containerRuntimeVersion" schema/NonBlankString]
   ["kubeletVersion" schema/NonBlankString]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ClusterMetadata
  [:and Metadata [:map ["namespace" {:optional true} :nil]]])

(def ^{:stratum 1} NamespacedMetadata
  [:and Metadata [:map ["namespace" schema/NonBlankString]]])

(def ^{:stratum 1} Resource
  [:map ["apiVersion" schema/NonBlankString] ["kind" schema/NonBlankString] ["metadata" Metadata]])

(def ^{:stratum 1} PodSpec
  [:map
   ["nodeName" schema/NonBlankString]
   ["containers" [:vector {:min 1} Container]]
   ["initContainers" {:optional true} [:vector Container]]
   ["ephemeralContainers" {:optional true} [:vector Container]]])

(def ^{:stratum 1} PodStatus
  [:map
   ["containerStatuses" [:vector {:min 1} ContainerStatus]]
   ["initContainerStatuses" {:optional true} [:vector ContainerStatus]]
   ["ephemeralContainerStatuses" {:optional true} [:vector ContainerStatus]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} Namespace
  [:and Resource [:map ["apiVersion" [:= "v1"]] ["kind" [:= "Namespace"]]
                  ["metadata" ClusterMetadata]]])

(def ^{:stratum 2} Secret
  [:and Resource
   [:map ["apiVersion" [:= "v1"]] ["kind" [:= "Secret"]]
    ["metadata" [:map ["namespace" schema/NonBlankString]
                  ["resourceVersion" schema/NonBlankString]]]
    ["data" {:optional true} [:map-of :string :string]]
    ["type" {:optional true} :string]
    ["immutable" {:optional true} :boolean]]])

(def ^{:stratum 2} Node
  [:and Resource [:map ["apiVersion" [:= "v1"]] ["kind" [:= "Node"]]
                  ["metadata" ClusterMetadata]
                  ["status" [:map ["nodeInfo" NodeInfo]]]]])

(def ^{:stratum 2} Pod
  [:and Resource [:map ["apiVersion" [:= "v1"]] ["kind" [:= "Pod"]]
                  ["metadata" NamespacedMetadata]
                  ["spec" PodSpec] ["status" PodStatus]]])

(comment ::kubernetes-producer-shapes)
