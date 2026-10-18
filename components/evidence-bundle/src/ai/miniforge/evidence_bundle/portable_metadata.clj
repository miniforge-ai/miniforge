;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.portable-metadata
  "Bound metadata before consumers walk data omitted by serialization and hashing."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} maximum-depth
  "Maximum combined payload and metadata nesting before recursive consumers."
  128)

(def ^{:stratum 0} maximum-nodes
  "Bound the combined payload and metadata walk independently of wire size."
  65536)

(defn- ^{:stratum 0} portable? [value]
  (not (anomaly/anomaly? (artifact/content-digest value))))

(defn- ^{:stratum 0} children [value metadata depth]
  (let [items (if (coll? value) (seq value) [])
        reachable (cond-> items metadata (conj metadata))]
    (map #(vector % (inc depth)) reachable)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} valid?
  "Validate metadata only after the enclosing payload passes the portable codec."
  [value]
  (loop [pending [[value 0]] visited 0]
    (if-let [[item depth] (first pending)]
      (let [metadata (meta item)]
        (cond
          (or (>= visited maximum-nodes) (> depth maximum-depth)) false
          (and metadata (not (portable? metadata))) false
          :else (recur (concat (children item metadata depth) (next pending)) (inc visited))))
      true)))

(comment
  (valid? (with-meta [] {:source :test})))
