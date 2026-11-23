;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-opsv.kubernetes-reference-schema-builder
  "Derive dependency admission from the same native paths used for collection."
  (:require [ai.miniforge.phase-opsv.kubernetes-name-schema :as names]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} descend [[_ path :as descriptor]]
  (assoc descriptor 1 (subvec path 1)))

(defn- ^{:stratum 0} path-head [[_ path _]] (first path))

(defn- ^{:stratum 0} child-head [[_ [_ field] _]] field)

(defn ^{:stratum 0} closed-map-fields [fields]
  (into [:map {:closed true}]
        (for [field fields] [field {:optional true} :map])))

(defn- ^{:stratum 0} referent [[_ _ name-key policy]]
  (let [optional-type (if (:reference/optional? policy) :boolean :nil)
        data-key (:reference/data-key policy)]
    (cond-> [:map [name-key names/ResourceName] ["optional" {:optional true} optional-type]]
      data-key (conj [data-key [:re #"\A[-._a-zA-Z0-9]+\z"]]))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} source-fields [descriptors]
  (set (map path-head descriptors)))

(defn ^{:stratum 1} child-source-fields [descriptors]
  (set (map child-head descriptors)))

(defn ^{:stratum 1} source-schema [descriptors]
  (if (empty? (second (first descriptors)))
    (referent (first descriptors))
    (into [:map]
          (for [[field group] (group-by path-head descriptors)]
            [field {:optional true} (source-schema (map descend group))]))))

(comment ::native-reference-path-schema)
