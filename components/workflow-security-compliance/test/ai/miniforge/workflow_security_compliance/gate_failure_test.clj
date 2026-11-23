;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow-security-compliance.gate-failure-test
  "A security-compliance phase its gates failed is not marked completed."
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.workflow-security-compliance.interface]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gate-failures
  "The two projections the workflow's gate validation puts on a phase map."
  {:denied {:phase/status :failed}
   :unacknowledged {:status :failed :phase/status :failed
                    :phase/decision-unacknowledged {:anomaly/type :unavailable}}})

(defn- ^{:stratum 0} leave [projection]
  (let [interceptor (phase/get-phase-interceptor {:phase :sec-parse-scan :gates [:review-approved]})]
    ((:leave interceptor)
     {:phase (merge {:name :sec-parse-scan :status :completed
                     :started-at (System/currentTimeMillis)
                     :result {:status :success :output {:violations []}}}
                    projection)
      :execution/metrics {}})))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} configured-gate-failure-is-not-completed
  (let [passed (leave nil)]
    (is (= :completed (get-in passed [:phase :status])) "precondition: a passing phase completes")
    (is (some #{:sec-parse-scan} (get-in passed [:execution :phases-completed]))))
  (doseq [[failure projection] gate-failures]
    (testing (str failure " gate decision")
      (let [out (leave projection)]
        (is (= :failed (get-in out [:phase :status])))
        (is (not (some #{:sec-parse-scan} (get-in out [:execution :phases-completed]))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.workflow-security-compliance.gate-failure-test))
