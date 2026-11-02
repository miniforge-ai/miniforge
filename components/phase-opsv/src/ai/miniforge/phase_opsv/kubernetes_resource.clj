;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-resource
  "Stable identity and configuration projections for admitted Kubernetes resources.
   API status and bookkeeping metadata are not desired configuration.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private configuration-metadata ["labels" "annotations"])

(defn ^{:stratum 0} uid [resource] (get-in resource ["metadata" "uid"]))

(defn ^{:stratum 0} name-of [resource] (get-in resource ["metadata" "name"]))

(defn ^{:stratum 0} namespace-of [resource] (get-in resource ["metadata" "namespace"]))

(defn- ^{:stratum 0} secret? [resource]
  (and (= "v1" (get resource "apiVersion")) (= "Secret" (get resource "kind"))))

(defn- ^{:stratum 0} secret-version [resource]
  ;; The boundary requires this server revision; never hash Secret values or annotations.
  (let [version (get-in resource ["metadata" "resourceVersion"])]
    {:secret/resource-version version}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} identity-key [resource]
  [(get resource "apiVersion") (get resource "kind")
   (namespace-of resource) (name-of resource) (uid resource)])

(defn- ^{:stratum 1} desired-configuration [resource]
  (let [metadata (select-keys (get resource "metadata") configuration-metadata)]
    (assoc (dissoc resource "metadata" "status") "metadata" metadata)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} configuration [resource]
  (if (secret? resource) (secret-version resource) (desired-configuration resource)))

(comment
  (identity-key {"apiVersion" "v1" "kind" "Namespace"
                 "metadata" {"name" "staging" "uid" "namespace-uid"}}))
