;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reference-test-support
  "Pod API-shaped configuration references without resource values or I/O."
  (:require [ai.miniforge.phase-opsv.kubernetes-container-test-support :as containers]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as captured]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} volume-cases
  "Independent native-path expectations shared by collection and admission tests."
  [[["configMap"] "name" "ConfigMap" true]
   [["secret"] "secretName" "Secret" true]
   [["csi" "nodePublishSecretRef"] "name" "Secret" false]
   [["azureFile"] "secretName" "Secret" false]
   [["cephfs" "secretRef"] "name" "Secret" false]
   [["cinder" "secretRef"] "name" "Secret" false]
   [["flexVolume" "secretRef"] "name" "Secret" false]
   [["iscsi" "secretRef"] "name" "Secret" false]
   [["rbd" "secretRef"] "name" "Secret" false]
   [["scaleIO" "secretRef"] "name" "Secret" false]
   [["storageos" "secretRef"] "name" "Secret" false]
   [["projected" "sources" 0 "secret"] "name" "Secret" true]
   [["projected" "sources" 0 "configMap"] "name" "ConfigMap" true]])

(def ^{:stratum 0} ^:private volume-fields
  "Required surrounding fields from the native core/v1 volume sources."
  {"csi" {"driver" "storage.example.invalid"}
   "azureFile" {"shareName" "configuration"}
   "cephfs" {"monitors" ["storage.example.invalid:6789"]}
   "cinder" {"volumeID" "volume-1"}
   "flexVolume" {"driver" "example.invalid/storage"}
   "iscsi" {"targetPortal" "192.0.2.1:3260"
            "iqn" "iqn.2026-01.invalid.example:storage"
            "lun" 0}
   "rbd" {"monitors" ["storage.example.invalid:6789"]
          "image" "configuration"}
   "scaleIO" {"gateway" "https://storage.example.invalid"
              "system" "staging"}
   "projected" {"sources" [{}]}})

(def ^{:stratum 0} ^:private key-by-source
  "Keys actually present in the captured ConfigMap and Secret fixtures."
  {"configMapKeyRef" "workers"
   "secretKeyRef" "token"})

(defn ^{:stratum 0} referent [name optional?]
  {"name" name
   "optional" optional?})

(defn ^{:stratum 0} local-reference [name] {"name" name})

(defn ^{:stratum 0} container [name environment environment-from]
  (assoc (containers/container name) "env" environment "envFrom" environment-from))

(defn ^{:stratum 0} pod [spec]
  (update (captured/pod) "spec" merge spec))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} volume [path name-key name]
  (let [root (first path)
        fields (get volume-fields root {})
        base {"name" "configuration"
              root fields}]
    (assoc-in base (conj path name-key) name)))

(defn ^{:stratum 1} environment-from [field name optional?]
  (hash-map field (referent name optional?)))

(defn ^{:stratum 1} environment [field name optional?]
  (let [value (assoc (referent name optional?) "key" (get key-by-source field))]
    {"name" "SETTING"
     "valueFrom" {field value}}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} api-pods [optional?]
  (let [environment [(environment "configMapKeyRef" "settings" optional?)]
        environment-from [(environment-from "secretRef" "credentials" false)]
        container (container "service" environment environment-from)
        observed (assoc-in (pod {"containers" [container]}) ["metadata" "labels"] {"app" "service"})
        result (captured/resource-list "PodList" [observed])]
    (assoc result "metadata" {"resourceVersion" "42"})))

(comment ::pod-reference-fixtures)
