;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reference-sources
  "Native reference descriptors: kind, path, name key, and selector policy.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private optional-selector
  "Only native selectors that declare an optional field may omit a resource."
  {:reference/optional? true})

(def ^{:stratum 0} ^:private key-selector
  "Environment key selectors require a data key and support explicit optionality."
  {:reference/optional? true
   :reference/data-key "key"})

(def ^{:stratum 0} image-pull-sources [["Secret" [] "name"]])

(defn- ^{:stratum 0} reference [kind namespace name optional?]
  {:reference/kind kind
   :reference/namespace namespace
   :reference/name name
   :reference/optional? optional?})

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} environment-sources
  [["ConfigMap" ["valueFrom" "configMapKeyRef"] "name" key-selector]
   ["Secret" ["valueFrom" "secretKeyRef"] "name" key-selector]])

(def ^{:stratum 1} environment-from-sources
  [["ConfigMap" ["configMapRef"] "name" optional-selector]
   ["Secret" ["secretRef"] "name" optional-selector]])

(def ^{:stratum 1} volume-sources
  [["ConfigMap" ["configMap"] "name" optional-selector]
   ["Secret" ["secret"] "secretName" optional-selector]
   ["Secret" ["csi" "nodePublishSecretRef"] "name"]
   ["Secret" ["azureFile"] "secretName"]
   ["Secret" ["cephfs" "secretRef"] "name"]
   ["Secret" ["cinder" "secretRef"] "name"]
   ["Secret" ["flexVolume" "secretRef"] "name"]
   ["Secret" ["iscsi" "secretRef"] "name"]
   ["Secret" ["rbd" "secretRef"] "name"]
   ["Secret" ["scaleIO" "secretRef"] "name"]
   ["Secret" ["storageos" "secretRef"] "name"]])

(def ^{:stratum 1} projected-sources
  [["ConfigMap" ["configMap"] "name" optional-selector]
   ["Secret" ["secret"] "name" optional-selector]])

(defn- ^{:stratum 1} source-reference [namespace source [kind path name-key policy]]
  (when-let [referent (get-in source path)]
    (let [optional? (and (:reference/optional? policy) (true? (get referent "optional")))]
      (reference kind namespace (get referent name-key) (boolean optional?)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} references [namespace descriptors source]
  (keep (partial source-reference namespace source) descriptors))

(comment ::pod-api-reference-paths)
