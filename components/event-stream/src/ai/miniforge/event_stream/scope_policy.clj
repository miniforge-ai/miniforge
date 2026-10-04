;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.scope-policy
  "Resolve authoritative scope without inferring identity from cross-references."
  (:require [ai.miniforge.event-stream.commit-model :as model]
            [ai.miniforge.event-stream.scope-catalog :as catalog]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} inherited? [event-type]
  (catalog/inherited? event-type))

(defn ^{:stratum 0} scope
  "Resolve one non-nil scope. The publication boundary validates envelope shapes.
   Inherited families must name a supported :scope/type; never infer it from keys."
  [event]
  (let [scope-type (catalog/type-for event)
        field (catalog/field scope-type)
        id (get event field)]
    (if (and field (some? id))
      [scope-type id]
      (model/failure :invalid-input :publication/invalid-scope event))))

(comment
  (scope {:event/type :workflow/started :workflow/id (random-uuid)}))
