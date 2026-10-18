;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.gate-publication-integration-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.workflow.execution-lifecycle :as lifecycle]
            [ai.miniforge.workflow.execution-transition :as transition]
            [ai.miniforge.workflow.gate-test-support :as f]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is]]
            [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-journal [check]
  (fs/with-temp-dir [root {:prefix "workflow-gate-journal-"}]
    (let [directory (str (fs/canonicalize root))
          stream (events/create-current-event-stream {:journal-directory directory})]
      (try+ (check directory stream)
            (finally (events/close-current-event-stream! stream))))))

(defn- ^{:stratum 0} reopened-history [directory]
  (let [stream (events/create-current-event-stream {:journal-directory directory})]
    (try+ (events/get-events stream)
          (finally (events/close-current-event-stream! stream)))))

(defn- ^{:stratum 0} observe-leave [calls ctx]
  (swap! calls conj (:phase ctx))
  ctx)

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} assert-durable-decision [directory stream]
  (let [calls (atom [])
        interceptor (f/lifecycle-interceptor (partial observe-leave calls))
        ctx (f/context stream)
        [_ result] (lifecycle/execute-phase-lifecycle interceptor ctx)
        history (events/get-events stream)
        event (first history)]
    (is (= :phase/succeed (transition/determine-phase-event {} result)))
    (is (= [result] @calls))
    (is (= [:gate/decision] (mapv :event/type history)))
    (is (= [0] (mapv :event/sequence-number history)))
    (is (= "1.0.0" (:event/version event)))
    (is (= :workflow (:scope/type event)))
    (is (= (:execution/id ctx) (:workflow/id event)))
    (is (= :review (:gate/phase event)))
    (is (= (:phase/decision-envelope result) (:gate/decision-envelope event)))
    (events/close-current-event-stream! stream)
    (is (= history (reopened-history directory)))))

(defn- ^{:stratum 1} assert-closed-journal-blocks [_directory stream]
  (let [calls (atom [])
        interceptor (f/lifecycle-interceptor (partial observe-leave calls))]
    (events/close-current-event-stream! stream)
    (let [[_ result] (lifecycle/execute-phase-lifecycle interceptor (f/context stream))]
      (is (= :phase/fail (transition/determine-phase-event {} result)))
      (is (anomaly/any-anomaly? (:phase/gate-publication-failure result)))
      (is (empty? @calls)))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} current-decision-is-durable-before-dependent-leave
  (with-journal assert-durable-decision))

(deftest ^{:stratum 2} closed-current-journal-prevents-phase-advancement
  (with-journal assert-closed-journal-blocks))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow.gate-publication-integration-test))
