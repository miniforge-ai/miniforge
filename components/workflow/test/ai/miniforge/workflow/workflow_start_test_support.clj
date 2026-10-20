;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.workflow-start-test-support
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.coerce.interface :as coerce]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow.checkpoint-store :as checkpoints]
            [ai.miniforge.workflow.phase-event-test-support :as journal]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [ai.miniforge.workflow.runner :as runner]
            [ai.miniforge.workflow.runner-cleanup :as cleanup]
            [ai.miniforge.workflow.runner-environment :as environment]
            [ai.miniforge.workflow.runner-events :as publishers]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow []
  {:workflow/id :start-admission :workflow/version "1.0.0"
   :workflow/pipeline [{:phase fixture/runner-test-plan}]})

(defn- ^{:stratum 0} options [stream overrides]
  (merge {:event-stream stream :workflow/intent {:intent/operation :verify}} overrides))

(defn- ^{:stratum 0} record-enter [calls enter & args]
  (swap! calls conj :phase-enter)
  (apply enter args))

(defn ^{:stratum 0} throw-construction [& _args]
  (throw (IllegalStateException. "Start construction failed")))

(defn ^{:stratum 0} throw-fatal [& _args]
  (throw (AssertionError. "Fatal startup failure")))

(defn ^{:stratum 0} assert-refused [result calls]
  (let [error (last (:execution/errors result))
        persisted (checkpoints/load-checkpoint-data (:execution/id result) {})]
    (is (= :failed (:execution/status result)))
    (is (= :workflow-start-publication-failed (:type error)))
    (is (= :workflow/started (:event/type error)))
    (is (anomaly/any-anomaly? (:anomaly error)))
    (is (= [:cleanup] @calls))
    (is (empty? (:execution/phase-results result)))
    (is (= :failed (get-in persisted [:machine-snapshot :execution/status])))
    (is (= (coerce/stringify-instants (:execution/errors result))
           (get-in persisted [:machine-snapshot :execution/errors])))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} execute [stream overrides calls]
  (let [enter phase/enter-context]
    (with-redefs [environment/acquire-execution-environment! (constantly nil)
                  environment/persist-workspace-at-phase-boundary! (constantly nil)
                  phase/enter-context (partial record-enter calls enter)
                  cleanup/post-workflow-cleanup! (partial journal/observe calls :cleanup nil)
                  publishers/start-phase-heartbeat! (constantly nil)]
      (runner/run-pipeline (workflow) {} (options stream overrides)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} verify-missing-intent [stream _options]
  (let [calls (atom [])]
    (assert-refused (execute stream {:workflow/intent nil} calls) calls)
    (is (not-any? #{:workflow/started :workflow/phase-started}
                 (map :event/type (events/get-events stream))))))

(defn ^{:stratum 2} verify-closed [stream _options]
  (events/close-current-event-stream! stream)
  (let [calls (atom [])]
    (assert-refused (execute stream {} calls) calls)
    (is (empty? (events/get-events stream)))))

(defn ^{:stratum 2} verify-construction [stream _options]
  (let [calls (atom [])]
    (with-redefs [events/workflow-started throw-construction]
      (assert-refused (execute stream {} calls) calls))))

(defn ^{:stratum 2} verify-fatal [stream _options]
  (let [calls (atom [])]
    (with-redefs [events/workflow-started throw-fatal]
      (is (thrown? AssertionError (execute stream {} calls))))
    (is (= [:cleanup] @calls))))

(defn ^{:stratum 2} verify-accepted [stream _options]
  (let [calls (atom [])]
    (execute stream {} calls)
    (is (= [:phase-enter :cleanup] @calls))
    (is (= :workflow/started (:event/type (first (events/get-events stream)))))))

(defn ^{:stratum 2} verify-skipped [stream _options]
  (let [calls (atom [])
        result (execute stream {:workflow/intent nil :skip-lifecycle-events true} calls)]
    (is (= [:phase-enter :cleanup] @calls))
    (is (not-any? #(= :workflow-start-publication-failed (:type %)) (:execution/errors result)))
    (is (not-any? #(= :workflow/started (:event/type %)) (events/get-events stream)))))
