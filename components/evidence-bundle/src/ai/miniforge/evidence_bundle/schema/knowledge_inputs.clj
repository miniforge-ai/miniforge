;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.knowledge-inputs
  "Portable knowledge-input records from N6 section 2.1."
  (:require [ai.miniforge.evidence-bundle.schema.domain :as domain]
            [ai.miniforge.evidence-bundle.schema.governance-values :as values]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} record-schema
  [:map
   [:knowledge/id uuid?]
   [:knowledge/type [:enum :feature-pack :policy-pack :agent-profile-pack :doc]]
   [:knowledge/trust-level [:fn (partial contains? domain/trust-levels)]]
   [:knowledge/authority [:enum :authority/instruction :authority/data]]
   [:knowledge/source string?]
   [:knowledge/content-hash values/sha256]
   [:knowledge/signature {:optional true} string?]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} valid? (m/validator (values/vector-of record-schema)))
