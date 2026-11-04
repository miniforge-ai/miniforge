;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-workload-identity-schema
  (:require [ai.miniforge.phase-opsv.kubernetes-controller-schema :as controller]
            [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource]
            [malli.util :as mu]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} OwnerReference
  [:and controller/OwnerReference [:map ["name" names/ResourceName]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Metadata
  [:and resource/NamespacedMetadata
   [:map ["name" names/ResourceName]
    ["namespace" names/NamespaceName]
    ["ownerReferences" {:optional true} [:vector OwnerReference]]]])

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} native-item [api-version kind]
  (mu/optional-keys
    (mu/merge resource/Resource
      [:map ["apiVersion" [:= api-version]] ["kind" [:= kind]] ["metadata" Metadata]])
    ["apiVersion" "kind"]))

(comment ::native-identity-including-unmanaged-and-unrelated-workloads)
