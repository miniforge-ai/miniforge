;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.opsv-assembly-state
  "Pure record construction and conditional assembly-map transitions.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private reference-keys
  [:opsv/event-refs :opsv/artifact-refs :opsv/grant-refs])

(defn ^{:stratum 0} initial-record [bundle-id workflow-id]
  {:evidence-bundle/id bundle-id
   :evidence-bundle/workflow-id workflow-id
   :opsv.assembly/status :assembling
   :opsv/event-refs #{}
   :opsv/artifact-refs #{}
   :opsv/grant-refs #{}
   :opsv/governed-effects #{}})

(defn ^{:stratum 0} insert-if-absent [state bundle-id record]
  (if (contains? state bundle-id) state (assoc state bundle-id record)))

(defn- ^{:stratum 0} reference-collection [value]
  (cond
    (nil? value) []
    (or (sequential? value) (set? value)) value
    :else [value]))

(defn- ^{:stratum 0} merge-field [record key values]
  (update record key into values))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} normalized-material [material]
  (let [values (map (comp reference-collection (partial get material)) reference-keys)
        effects (reference-collection (get-in material [:opsv/actuation :governed-effects]))]
    (assoc (zipmap reference-keys values) :opsv/governed-effects effects)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} accumulate [state bundle-id material]
  (let [record (get state bundle-id)]
    (if (= :assembling (:opsv.assembly/status record))
      (assoc state bundle-id (reduce-kv merge-field record (normalized-material material)))
      state)))

(comment
  (accumulate {} :missing {}))
