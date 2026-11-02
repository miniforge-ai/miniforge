;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reference-test-support
  "Pod API-shaped configuration references without resource values or I/O.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} referent [name optional?]
  {"name" name
   "optional" optional?})

(defn ^{:stratum 0} container [name environment environment-from]
  {"name" name
   "image" "registry.invalid/service:release"
   "env" environment
   "envFrom" environment-from})

(defn ^{:stratum 0} pod [spec]
  {"apiVersion" "v1"
   "kind" "Pod"
   "metadata" {"name" "service-a"
               "namespace" "staging"}
   "spec" spec})

(defn ^{:stratum 0} volume [path name-key name]
  (let [base (cond-> {"name" "configuration"}
               (= "projected" (first path)) (assoc-in ["projected" "sources"] [{}]))]
    (assoc-in base (conj path name-key) name)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} environment [field name optional?]
  (let [value (referent name optional?)]
    {"name" "SETTING"
     "valueFrom" {field value}}))

(comment ::pod-reference-fixtures)
