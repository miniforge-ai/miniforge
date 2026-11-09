;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-replica-payload
  "One canonical Deployment replica wire vocabulary, shared by planning and execution."
  (:require [ai.miniforge.phase-opsv.kubernetes-read-plan :as reads]
            [ai.miniforge.phase-opsv.kubernetes-resource :as resource]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} media-type "RFC 6902, not strategic merge or server-side apply." "application/json-patch+json")

(def ^{:stratum 0} uid-path "Fence replacement objects even when a name is reused." "/metadata/uid")

(def ^{:stratum 0} revision-path "Fence changes made since the fresh capture." "/metadata/resourceVersion")

(def ^{:stratum 0} replicas-path "The only mutable field supported by this request builder." "/spec/replicas")

(defn ^{:stratum 0} operation [op path value]
  {"op" op "path" path "value" value})

(defn ^{:stratum 0} resource-reference [deployment]
  {:api-version "apps/v1"
   :kind "Deployment"
   :namespace (resource/namespace-of deployment)
   :name (resource/name-of deployment)
   :uid (resource/uid deployment)})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} request [reference revision desired]
  (let [path (first (reads/request "Deployment" (:namespace reference) false (:name reference)))
        body [(operation "test" uid-path (:uid reference))
              (operation "test" revision-path revision)
              (operation "replace" replicas-path desired)]]
    {:method :patch :path path :media-type media-type :body body}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prepare [deployment desired]
  (request (resource-reference deployment) (get-in deployment ["metadata" "resourceVersion"]) desired))

(comment ::canonical-conditional-patch-data-not-authority)
