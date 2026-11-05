;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-volume-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NamedReference [:map ["name" names/NamespaceName]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Volume
  [:and NamedReference
   [:map ["projected" {:optional true} [:map ["sources" [:vector [:map-of :string :map]]]]]]])

(def ^{:stratum 1} Container
  [:map ["name" schema/NonBlankString]
   ["volumeMounts" {:optional true}
    [:vector [:and NamedReference [:map ["mountPath" schema/NonBlankString]
                                   ["readOnly" {:optional true} :boolean]]]]]
   ["volumeDevices" {:optional true}
    [:vector [:and NamedReference [:map ["devicePath" schema/NonBlankString]]]]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} PodSpec
  [:map
   ["containers" [:vector {:min 1} Container]]
   ["initContainers" {:optional true} [:vector Container]]
   ["ephemeralContainers" {:optional true} [:vector Container]]
   ["volumes" {:optional true} [:vector Volume]]])

(comment ::validate-volume-binding-sections-not-the-entire-kubernetes-pod-contract)
