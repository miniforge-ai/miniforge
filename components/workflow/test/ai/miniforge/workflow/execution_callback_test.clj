;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.execution-callback-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.execution :as execution]
            [ai.miniforge.workflow.phase-test-support :as fixture]
            [ai.miniforge.workflow.runner-preparation :as preparation]
            [clojure.test :refer [deftest is use-fixtures]]))

(use-fixtures :each fixture/with-workflow-phase-test-support)

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} workflow []
  {:workflow/id :callback-refusal :workflow/version "1.0.0"
   :workflow/pipeline [{:phase fixture/runner-test-plan}]})

(defn- ^{:stratum 0} execute [definition pipeline callbacks]
  (execution/execute-phase-step
    pipeline (context/create-context definition {} {}) callbacks context/merge-metrics
    context/transition-to-completed context/transition-to-failed))

(defn- ^{:stratum 0} attach-progress [report ctx _interceptor]
  (assoc ctx :execution/report-progress! report))

(defn- ^{:stratum 0} capture-entry [seen enter ctx]
  (reset! seen (:execution/report-progress! ctx))
  (enter ctx))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} progress-port-reaches-the-real-phase-entry
  (let [definition (workflow)
        pipeline (preparation/build-pipeline definition)
        seen (atom nil)
        enter (partial capture-entry seen (:enter (first pipeline)))
        prepared (assoc-in pipeline [0 :enter] enter)
        report (constantly nil)
        callbacks {:on-phase-start (constantly :legacy-observer-result)
                   :phase-context (partial attach-progress report)}
        result (execute definition prepared callbacks)]
    (is (identical? report @seen))
    (is (contains? (:execution/phase-results result) fixture/runner-test-plan))))

(deftest ^{:stratum 1} public-executor-honors-refusal-with-real-phase-lifecycle
  (doseq [boundary [:on-phase-start :on-phase-complete]]
    (let [definition (workflow)
          pipeline (preparation/build-pipeline definition)
          refusal (anomaly/anomaly :unavailable "publication unavailable" {})
          callbacks {boundary (constantly refusal)}
          result (execute definition pipeline callbacks)
          error (last (:execution/errors result))]
      (is (= :failed (:execution/status result)))
      (is (= refusal (:anomaly error)))
      (is (= boundary (:boundary error)))
      (is (= (= boundary :on-phase-complete)
             (contains? (:execution/phase-results result) fixture/runner-test-plan))))))

(deftest ^{:stratum 1} missing-interceptor-still-fails-before-notification
  (let [result (execute (workflow) [] {})]
    (is (= :failed (:execution/status result)))
    (is (= :missing-current-phase (:type (last (:execution/errors result)))))))
