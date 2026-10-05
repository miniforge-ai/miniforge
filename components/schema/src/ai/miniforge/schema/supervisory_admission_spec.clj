;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.schema.supervisory-admission-spec
  "N5-delta-1 current-write records, separate from historical projection shapes."
  (:require [ai.miniforge.schema.supervisory-records-spec :as records]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonBlankTitle
  [:and :string [:re #"(?s).*\S.*"]])

(def ^{:stratum 0} InterventionRecord
  "Complete record shape; initial-state and transition constraints are separate."
  [:and records/InterventionProjection
   [:map [:intervention/justification :string]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} SpecRecord
  [:and records/SpecProjection
   [:map
    [:spec/title NonBlankTitle]
    [:spec/origin :keyword]]])

(comment
  SpecRecord)
