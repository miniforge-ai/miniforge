;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.evidence-bundle.schema.gate-executions
  "Portable gate-execution records from N6 section 2.13."
  (:require [ai.miniforge.evidence-bundle.schema.gate-consistency :as consistency]
            [ai.miniforge.evidence-bundle.schema.gate-records :as records]
            [ai.miniforge.evidence-bundle.schema.gate-violation :as violation]
            [ai.miniforge.evidence-bundle.schema.governance-values :as values]
            [ai.miniforge.evidence-bundle.schema.resolution-trace :as trace]
            [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} record-schema
  [:map
   [:gate-execution/gate-id keyword?] [:gate-execution/phase keyword?]
   [:gate-execution/evaluation-id uuid?]
   [:gate-execution/allow-override? {:optional true} boolean?]
   [:gate-execution/outcome [:enum :passed :failed :waived]]
   [:gate-execution/binding records/binding-schema]
   [:gate-execution/packs (values/record-vector records/resolved-pack)]
   [:gate-execution/resolved-rules (values/record-vector qualified-keyword?)]
   [:gate-execution/resolution-trace (values/record-vector trace/record-schema)]
   [:gate-execution/violations (values/record-vector violation/record-schema)]
   [:gate-execution/waivers (values/record-vector records/waiver)]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} valid?
  (m/validator (values/record-vector [:and record-schema [:fn consistency/consistent?]])))
