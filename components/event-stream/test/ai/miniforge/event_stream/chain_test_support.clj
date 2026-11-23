;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.chain-test-support
  (:require [ai.miniforge.event-stream.commit-test-support :as support]))

;------------------------------------------------------------------------------ Layer 0

;; Independent examples from the contract, not keys derived from the schema under test.
(def ^{:stratum 0} required-fields
  {:chain/started [:chain/step-count]
   :chain/completed [:chain/step-count :chain/duration-ms]
   :chain/failed [:chain/error :failure/class]
   :chain/step-started [:step/id :step/index :step/workflow-id :workflow/id]
   :chain/step-completed [:step/id :step/index :workflow/id]
   :chain/step-failed [:step/id :step/index :workflow/id :chain/error :failure/class]
   :chain.edge/started [:edge/id :edge/from-workflow-id :edge/to-workflow-id :edge/bindings-count]
   :chain.edge/completed [:edge/id :edge/from-workflow-id :edge/to-workflow-id :edge/duration-ms]
   :chain.edge/failed [:edge/id :edge/from-workflow-id :edge/to-workflow-id :edge/failure-reason :failure/class]})

(defn- ^{:stratum 0} lifecycle-fields []
  (let [reason (:message (support/draft))]
    {:chain/step-count 1
     :chain/duration-ms 0
     :chain/error reason
     :failure/class :failure.class/task-code
     :step/id :first
     :step/index 0
     :step/workflow-id :example/workflow
     :workflow/id (random-uuid)
     :edge/id (random-uuid)
     :edge/from-workflow-id (random-uuid)
     :edge/to-workflow-id (random-uuid)
     :edge/bindings-count 1
     :edge/duration-ms 0
     :edge/failure-reason reason}))

(defn- ^{:stratum 0} identity-fields [event-type]
  {:event/type event-type
   :event/version "2.0.0"
   :scope/type :chain
   :chain/run-id (random-uuid)
   :chain/definition-id :example/chain
   :chain/definition-version "1.2.0"})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} payload [event-type]
  (let [fields (lifecycle-fields)
        required (get required-fields event-type)
        lifecycle (select-keys fields required)]
    (merge (identity-fields event-type) lifecycle)))

(comment
  (payload :chain/started))
