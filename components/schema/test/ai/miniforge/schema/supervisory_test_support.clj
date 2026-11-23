;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.supervisory-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} spec-projection []
  {:spec/id (random-uuid)
   :spec/title "Projection fixture"
   :spec/status :draft
   :spec/created-at #inst "2026-10-04"
   :spec/updated-at #inst "2026-10-04"})

(defn ^{:stratum 0} intervention-projection []
  {:intervention/id (random-uuid)
   :intervention/type :pause
   :intervention/target-type :workflow
   :intervention/target-id (random-uuid)
   :intervention/requested-by "fixture-operator"
   :intervention/request-source :tui
   :intervention/state :proposed
   :intervention/requested-at #inst "2026-10-04"
   :intervention/updated-at #inst "2026-10-04"})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} spec-record []
  (assoc (spec-projection) :spec/origin :miniforge))

(defn ^{:stratum 1} intervention-record []
  (assoc (intervention-projection) :intervention/justification "Fixture rationale"))

(comment
  (spec-record))
