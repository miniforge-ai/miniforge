;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-dependency-schema
  "Admit every supported dependency path before deriving additional API reads."
  (:require [ai.miniforge.phase-opsv.kubernetes-reference-schema-builder :as builder]
            [ai.miniforge.phase-opsv.kubernetes-reference-sources :as sources]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.phase-opsv.kubernetes-list-schema :as lists]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Container
  (let [value-from-fields (conj (builder/child-source-fields sources/environment-sources) "fieldRef" "resourceFieldRef")
        value-from-shell (builder/closed-map-fields value-from-fields)
        source (builder/source-schema sources/environment-sources)
        environment [:and source [:map ["valueFrom" {:optional true} value-from-shell]]]
        from-fields (builder/source-fields sources/environment-from-sources)
        from-shell (conj (builder/closed-map-fields from-fields) ["prefix" {:optional true} :string])]
    [:map
     ["env" {:optional true} [:vector environment]]
     ["envFrom" {:optional true} [:vector [:and from-shell (builder/source-schema sources/environment-from-sources)]]]]))

(def ^{:stratum 0} Volume
  (let [volume-fields (conj (builder/source-fields sources/volume-sources) "emptyDir" "projected" "downwardAPI")
        projected-fields (conj (builder/source-fields sources/projected-sources) "downwardAPI" "serviceAccountToken")
        volume-shell (conj (builder/closed-map-fields volume-fields) ["name" schema/NonBlankString])
        projected-shell (builder/closed-map-fields projected-fields)
        projected-source [:and projected-shell (builder/source-schema sources/projected-sources)]]
    [:and volume-shell (builder/source-schema sources/volume-sources)
     [:map ["projected" {:optional true} [:map ["sources" [:vector projected-source]]]]]]))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} PodDependencies
  [:map
   ["nodeName" names/ResourceName]
   ["containers" [:vector {:min 1} Container]]
   ["initContainers" {:optional true} [:vector Container]]
   ["ephemeralContainers" {:optional true} [:vector Container]]
   ["volumes" {:optional true} [:vector Volume]]
   ["imagePullSecrets" {:optional true} [:vector (builder/source-schema sources/image-pull-sources)]]])

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} PodList
  [:and lists/PodList
   [:map
    ["metadata" lists/ListMetadata]
    ["items" [:vector [:map ["metadata" [:map ["namespace" names/NamespaceName]]]
                       ["spec" PodDependencies]]]]]])

(comment ::complete-known-reference-admission)
