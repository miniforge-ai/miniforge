;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.projection
  "Pure projections from producer metadata to optional evidence fields.
   Projection preserves non-nil values; validation owns their admissibility.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private execution-fields
  [:evidence/execution-mode :evidence/runtime-class :evidence/task-started-at
   :evidence/task-finished-at :evidence/image-digest])

(def ^{:stratum 0} ^:private pr-fields
  {:outcome/pr-number [:pr-number :number]
   :outcome/pr-url [:pr-url :url]
   :outcome/pr-status [:pr-status :status]
   :outcome/pr-merged-at [:pr-merged-at :merged-at]})

(defn ^{:stratum 0} present
  "Omit unavailable top-level fields only; preserve false and nested nils."
  [fields]
  (into {} (remove (comp nil? val)) fields))

(defn- ^{:stratum 0} pr-entry [pr-info [target [producer-key legacy-key]]]
  [target (get pr-info producer-key (get pr-info legacy-key))])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execution [workflow-state]
  (-> (:execution/output workflow-state)
      (select-keys execution-fields)
      present))

(defn ^{:stratum 1} pull-request
  "Release keys take precedence, including explicit nil; legacy keys remain readable."
  [pr-info]
  (->> pr-fields
       (map (partial pr-entry pr-info))
       (into {})
       present))
