;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.gate-failure-leave-test
  "Leave handlers never record or publish success for a phase its gates failed."
  (:require [ai.miniforge.phase.interface :as phase]
            [ai.miniforge.phase-software-factory.interface]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} gate-failures
  "The two projections the workflow's gate validation puts on a phase map."
  {:denied {:phase/status :failed}
   :unacknowledged {:status :failed :phase/status :failed
                    :phase/decision-unacknowledged {:anomaly/type :unavailable}}})

(def ^{:stratum 0} warning-only-at-cap
  "A warning-only rejection on the cycle that reaches the default churn cap,
   which the default policy turns into :accept-with-warnings."
  {:review/decision :changes-requested
   :review/blocking-issues []
   :review/warnings [{:severity :warning :description "Style nit"}]})

(defn- ^{:stratum 0} review-ctx [output gate-failure]
  {:phase (merge {:started-at (- (System/currentTimeMillis) 1000)
                  :iterations 1
                  :budget {:iterations 3}
                  :result {:output output :metrics {:tokens 1 :duration-ms 1}}}
                 gate-failure)
   :phase-config {:phase :review}
   :execution {:review-fingerprints [] :review-warning-only-cycles 1}
   :execution/phase-results {}
   :execution/input {:description "task"}
   :execution/metrics {}})

(defn- ^{:stratum 0} leave [phase-kw ctx]
  (let [emitted (atom nil)]
    (with-redefs [phase/emit-phase-completed! (fn [_ctx _phase data] (reset! emitted data))]
      [((:leave (phase/get-phase-interceptor {:phase phase-kw})) ctx) @emitted])))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} review-acceptance-cannot-complete-a-gate-failed-phase
  (let [[accepted] (leave :review (review-ctx warning-only-at-cap nil))]
    (is (= :accept-with-warnings (get-in accepted [:phase :verdict])))
    (is (= :completed (get-in accepted [:phase :status])) "precondition: acceptance completes"))
  (doseq [[failure projection] gate-failures
          output [warning-only-at-cap {:review/decision :approved}]]
    (testing (str failure " gate failure, review " (:review/decision output))
      (let [[out emitted] (leave :review (review-ctx output projection))]
        (is (= :failed (get-in out [:phase :status])))
        (is (not (some #{:review} (get-in out [:execution :phases-completed]))))
        (is (not= :success (:outcome emitted)))))))

(deftest ^{:stratum 1} explore-and-pr-monitor-do-not-complete-a-gate-failed-phase
  (doseq [[failure projection] gate-failures
          phase-kw [:explore :pr-monitor]]
    (testing (str failure " " phase-kw)
      (let [ctx {:phase (merge {:started-at (System/currentTimeMillis) :status :completed
                                :result {:status :success :output {}}}
                               projection)
                 :execution/metrics {}}
            [out] (leave phase-kw ctx)]
        (is (= :failed (get-in out [:phase :status])))
        (is (not (some #{phase-kw} (get-in out [:execution :phases-completed]))))))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.gate-failure-leave-test))
