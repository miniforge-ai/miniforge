;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.chain-fields-spec
  "Shared chain identity and lifecycle fields from N1 §2.32 and N3 §3.12.1."
  (:require [ai.miniforge.failure-classifier.interface :as failure]
            [ai.miniforge.schema.interface :as schema]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} NonNegativeLong [:and int? [:>= 0]])

(def ^{:stratum 0} DefinitionVersion
  [:and schema/NonBlankString [:not [:= "latest"]]])

(def ^{:stratum 0} Failure
  [:map
   [:chain/error :string]
   [:failure/class failure/FailureClass]])

(def ^{:stratum 0} Edge
  [:map
   [:edge/id :uuid]
   [:edge/from-workflow-id :uuid]
   [:edge/to-workflow-id :uuid]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Identity
  [:map
   [:event/type qualified-keyword?]
   [:event/version [:= "2.0.0"]]
   [:scope/type [:= :chain]]
   ;; N2 §14.4 retires this alias for current writes; historical records stay intact.
   [:chain/id {:optional true} [:not :any]]
   [:chain/run-id :uuid]
   [:chain/definition-id :keyword]
   [:chain/definition-version DefinitionVersion]
   [:workflow/id {:optional true} [:maybe :uuid]]])

(def ^{:stratum 1} StepOutcome
  [:map
   [:step/id :keyword]
   [:step/index NonNegativeLong]
   [:workflow/id :uuid]])

(comment
  Identity)
