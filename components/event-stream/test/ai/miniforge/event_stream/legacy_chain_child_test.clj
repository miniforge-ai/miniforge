;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.event-stream.legacy-chain-child-test
  (:require [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.event-stream.commit-test-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} child-reference-participates-in-sequence-allocation
  (let [stream (events/create-event-stream {:sinks []})
        child (random-uuid)
        opts {:workflow/id child :failure/class :failure.class/task-code}
        reason (:message (support/draft))
        history [(events/chain-step-started stream :chain :step 0 :workflow opts)
                 (events/create-envelope stream :workflow/started child reason)
                 (events/chain-step-completed stream :chain :step 0 opts)
                 (events/chain-step-failed stream :chain :step 0 reason opts)]
        run (events/chain-started stream :chain 1)]
    (is (= [0 1 2 3] (mapv :event/sequence-number history)))
    (is (= [child child child child] (mapv :workflow/id history)))
    (is (= :failure.class/task-code (:failure/class (last history))))
    (is (nil? (:workflow/id run)))
    (is (zero? (:event/sequence-number run)))))

(deftest ^{:stratum 0} omitted-child-reference-preserves-legacy-calls
  (let [stream (events/create-event-stream {:sinks []})
        reason (:message (support/draft))
        history [(events/chain-step-started stream :chain :step 0 :workflow)
                 (events/chain-step-completed stream :chain :step 0)
                 (events/chain-step-failed stream :chain :step 0 reason)]]
    (is (= [0 1 2] (mapv :event/sequence-number history)))
    (is (every? nil? (map :workflow/id history)))))

(comment
  ::legacy-child-sequencing)
