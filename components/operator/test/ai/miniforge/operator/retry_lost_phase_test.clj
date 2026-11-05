;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.operator.retry-lost-phase-test
  "`:retry-from-phase` to a phase whose checkpointed result did not read
   (and whose events are gone) reaches the same rewind `mf resume` does:
   the run's manifest records the phase, so it is known."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.operator.mechanism :as mechanism]
            [ai.miniforge.workflow-resume.interface :as wr]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} plan-lost-context
  "Reconstructed run parked at :implement, plan.edn unreadable, no events."
  [run-id]
  {:completed-phases []
   :phase-results {}
   :event-count 0
   :workflow-id run-id
   :workflow-spec {:name "canonical-sdlc" :version "1.0.0"}
   :checkpoint-manifest {:workflow/phases-completed [:plan]}
   :checkpoint-read-failures [(anomaly/anomaly :unavailable "plan.edn could not be read"
                                               {:checkpoint/path "/tmp/run/phases/plan.edn"
                                                :phase/name :plan})]
   :machine-snapshot {:execution/id run-id
                      :execution/status :running
                      :execution/current-phase :implement}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} retry-from-a-lost-phase-is-not-an-unknown-phase
  (let [run-id (random-uuid)
        interv {:intervention/target-id run-id
                :intervention/requested-by "op@example.invalid"
                :intervention/details {"phase" "plan"}}
        result (with-redefs [wr/reconstruct-context (fn [_ _] (plan-lost-context run-id))]
                 (mechanism/prepare-resume "/tmp/unused-events" interv :retry-from-phase))]
    (is (nil? (:failure/code result)))
    (is (= :plan (get-in result [:resume/plan :resume/from-phase])))
    (is (nil? (get-in result [:resume/plan :resume/machine-snapshot])))))

(comment ::retry-lost-phase)
