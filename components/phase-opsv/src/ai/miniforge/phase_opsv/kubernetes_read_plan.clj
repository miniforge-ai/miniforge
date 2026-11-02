;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-read-plan
  "Explicit native API reads for one admitted Pod selection."
  (:require [ai.miniforge.phase-opsv.kubernetes-configuration-schema :as configuration]
            [ai.miniforge.phase-opsv.kubernetes-api :as api]
            [ai.miniforge.phase-opsv.kubernetes-resource-schema :as resource])
  (:import [java.net URLEncoder]
           [java.nio.charset StandardCharsets]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private resource-types
  {"Namespace" ["namespaces" resource/Namespace]
   "Node" ["nodes" resource/Node]
   "ConfigMap" ["configmaps" configuration/ConfigMap]
   "Secret" ["secrets" resource/Secret]})

(defn- ^{:stratum 0} segment [value] (URLEncoder/encode value StandardCharsets/UTF_8))

(defn ^{:stratum 0} node-list [nodes]
  {"apiVersion" "v1"
   "kind" "NodeList"
   "items" nodes})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} namespace-path [namespace]
  (if namespace (str "/namespaces/" (segment namespace)) ""))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} request [kind namespace optional? name]
  (let [[plural schema] (get resource-types kind)
        prefix (namespace-path namespace)
        path (str api/core-api-prefix prefix "/" plural "/" (segment name))]
    [path schema [kind namespace name] optional?]))

(defn ^{:stratum 2} pods-path [namespace]
  (str api/core-api-prefix (namespace-path namespace) "/pods"))

(comment ::typed-kubernetes-read-requests)
