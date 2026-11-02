;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reference-test-support
  "Pod API-shaped configuration references without resource values or I/O."
  (:require [ai.miniforge.phase-opsv.kubernetes-container-test-support :as containers]
            [ai.miniforge.phase-opsv.kubernetes-test-support :as captured]))

;------------------------------------------------------------------------------ Layer 0

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

(defn ^{:stratum 0} volume [path name-key name]
  (let [base (cond-> {"name" "configuration"}
               (= "projected" (first path)) (assoc-in ["projected" "sources"] [{}]))]
    (assoc-in base (conj path name-key) name)))

;------------------------------------------------------------------------------ Layer 1

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
