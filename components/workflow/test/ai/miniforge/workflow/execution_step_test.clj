;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.execution-step-test
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.response.interface :as response]
            [ai.miniforge.workflow.execution-step-support :as support]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} callback-refusal-stops-the-boundary-and-preserves-its-cause
  (doseq [failure [(anomaly/anomaly :unavailable "journal unavailable" {})
                   (response/make-anomaly :anomalies/unavailable "journal unavailable")]
          boundary [:on-phase-start :on-phase-complete]]
    (let [start? (= boundary :on-phase-start)
          [result calls] (support/execute (when start? failure) (when-not start? failure) nil)
          error (last (:execution/errors result))]
      (is (= :failed (:execution/status result)))
      (is (= boundary (:boundary error)))
      (is (= :plan (:phase error)))
      (is (= failure (:anomaly error)))
      (is (= "journal unavailable" (:message error)))
      (is (= (if start? [:start] [:start :enter :complete]) calls))
      (is (= (not start?) (true? (:effect/retained result))))
      (is (= (if start? {} {:plan support/phase-result}) (:execution/phase-results result))))))

(deftest ^{:stratum 0} observer-results-preserve-normal-transition-and-dag-short-circuit
  (doseq [observer-result [nil false {:observed true}]]
    (is (= [:advanced [:start :enter :complete :dag :transition]]
           (support/execute observer-result observer-result nil)))
    (is (= [:dag-result [:start :enter :complete :dag]]
           (support/execute observer-result observer-result :dag-result)))))
