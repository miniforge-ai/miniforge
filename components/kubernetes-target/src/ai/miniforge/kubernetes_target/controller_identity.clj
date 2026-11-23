;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.kubernetes-target.controller-identity
  "Match observed Kubernetes controller edges without inferring identity from missing values."
  (:require [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0 :private true} reference-fields
  "Controller edges bind the complete immutable owner identity, not a reusable name."
  {"apiVersion" ["apiVersion"]
   "kind" ["kind"]
   "name" ["metadata" "name"]
   "uid" ["metadata" "uid"]})

(def ^{:stratum 0 :private true} namespace-field
  "Namespaced resources cannot prove ownership across a namespace boundary."
  ["metadata" "namespace"])

(defn- ^{:stratum 0} exact-text? [observed expected]
  (and (string? observed) (not (str/blank? observed)) (= observed expected)))

(defn ^{:stratum 0} controller [child]
  (let [owners (get-in child ["metadata" "ownerReferences"])
        controllers (when (vector? owners) (filter #(true? (get % "controller")) owners))]
    (when (= 1 (count controllers)) (first controllers))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} identifies? [parent reference]
  (every? (fn [[name path]] (exact-text? (get-in parent path) (get reference name)))
          reference-fields))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} owns? [parent child]
  (and (exact-text? (get-in parent namespace-field) (get-in child namespace-field))
       (identifies? parent (controller child))))
