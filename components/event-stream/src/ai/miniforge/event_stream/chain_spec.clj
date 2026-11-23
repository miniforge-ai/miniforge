;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.chain-spec
  "N3 §3.12.1 current-write payloads, not historical readers or lifecycle reducers."
  (:require [ai.miniforge.event-stream.chain-fields-spec :as fields]
            [ai.miniforge.failure-classifier.interface :as failure]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} Lifecycle
  [:multi {:dispatch :event/type}
   [:chain/started [:map [:chain/step-count fields/NonNegativeLong]]]
   [:chain/completed
    [:map
     [:chain/step-count fields/NonNegativeLong]
     [:chain/duration-ms fields/NonNegativeLong]]]
   [:chain/failed
    [:and fields/Failure
     [:map [:chain/failed-step {:optional true} [:maybe :keyword]]]]]
   [:chain/step-started
    [:and fields/StepOutcome [:map [:step/workflow-id :keyword]]]]
   [:chain/step-completed fields/StepOutcome]
   [:chain/step-failed [:and fields/StepOutcome fields/Failure]]
   [:chain.edge/started
    [:and fields/Edge [:map [:edge/bindings-count fields/NonNegativeLong]]]]
   [:chain.edge/completed
    [:and fields/Edge [:map [:edge/duration-ms fields/NonNegativeLong]]]]
   [:chain.edge/failed
    [:and fields/Edge
     [:map
      [:edge/failure-reason :string]
      [:failure/class failure/FailureClass]]]]])

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} Payload
  "Compose with envelope validation at admission. Run-level consistency is stateful."
  [:and fields/Identity Lifecycle])

(comment
  Payload)
