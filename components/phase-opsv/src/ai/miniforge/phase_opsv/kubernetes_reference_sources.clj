;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reference-sources
  "Native Pod API paths for ConfigMap and Secret read dependencies.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} environment-sources
  [["ConfigMap" ["valueFrom" "configMapKeyRef"] "name"]
   ["Secret" ["valueFrom" "secretKeyRef"] "name"]])

(def ^{:stratum 0} environment-from-sources
  [["ConfigMap" ["configMapRef"] "name"]
   ["Secret" ["secretRef"] "name"]])

(def ^{:stratum 0} volume-sources
  [["ConfigMap" ["configMap"] "name"]
   ["Secret" ["secret"] "secretName"]
   ["Secret" ["csi" "nodePublishSecretRef"] "name"]
   ["Secret" ["azureFile"] "secretName"]
   ["Secret" ["cephfs" "secretRef"] "name"]
   ["Secret" ["cinder" "secretRef"] "name"]
   ["Secret" ["flexVolume" "secretRef"] "name"]
   ["Secret" ["iscsi" "secretRef"] "name"]
   ["Secret" ["rbd" "secretRef"] "name"]
   ["Secret" ["scaleIO" "secretRef"] "name"]
   ["Secret" ["storageos" "secretRef"] "name"]])

(def ^{:stratum 0} projected-sources
  [["ConfigMap" ["configMap"] "name"]
   ["Secret" ["secret"] "name"]])

(def ^{:stratum 0} image-pull-sources [["Secret" [] "name"]])

(defn- ^{:stratum 0} reference [kind namespace name optional?]
  {:reference/kind kind
   :reference/namespace namespace
   :reference/name name
   :reference/optional? optional?})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} source-reference [namespace source [kind path name-key]]
  (when-let [referent (get-in source path)]
    (reference kind namespace (get referent name-key) (true? (get referent "optional")))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} references [namespace descriptors source]
  (keep (partial source-reference namespace source) descriptors))

(comment ::pod-api-reference-paths)
