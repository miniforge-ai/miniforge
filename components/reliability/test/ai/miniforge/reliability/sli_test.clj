;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.reliability.sli-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.reliability.interface :as rel]))

(def now (java.util.Date.))

;; ---------------------------------------------------------------------------- SLI-1: Workflow Success Rate

(deftest workflow-success-rate-test
  (testing "basic success rate"
    (is (= 1.0 (rel/compute-workflow-success-rate
                 [{:status :completed :timestamp now}
                  {:status :completed :timestamp now}]
                 :7d)))
    (is (= 0.5 (rel/compute-workflow-success-rate
                 [{:status :completed :timestamp now}
                  {:status :failed :timestamp now}]
                 :7d))))

  (testing "escalated counts as success"
    (is (= 1.0 (rel/compute-workflow-success-rate
                 [{:status :completed :timestamp now}
                  {:status :escalated :timestamp now}]
                 :7d))))

  (testing "empty returns 0.0"
    (is (= 0.0 (rel/compute-workflow-success-rate [] :7d))))

  (testing "ignores non-terminal statuses"
    (is (= 1.0 (rel/compute-workflow-success-rate
                 [{:status :completed :timestamp now}
                  {:status :running :timestamp now}]
                 :7d)))))

;; ---------------------------------------------------------------------------- SLI-3: Inner Loop Convergence

(deftest inner-loop-convergence-test
  (testing "all converge"
    (is (= 1.0 (rel/compute-inner-loop-convergence
                 [{:iterations 2 :success? true :timestamp now}
                  {:iterations 3 :success? true :timestamp now}]
                 :7d))))

  (testing "half converge"
    (is (= 0.5 (rel/compute-inner-loop-convergence
                 [{:iterations 2 :success? true :timestamp now}
                  {:iterations 5 :success? false :timestamp now}]
                 :7d))))

  (testing "skips entries without iterations"
    (is (= 1.0 (rel/compute-inner-loop-convergence
                 [{:iterations 2 :success? true :timestamp now}
                  {:success? false :timestamp now}]
                 :7d)))))

;; ---------------------------------------------------------------------------- SLI-4: Gate Pass Rate

(deftest gate-pass-rate-test
  (is (= 0.75 (rel/compute-gate-pass-rate
                [{:passed? true :timestamp now}
                 {:passed? true :timestamp now}
                 {:passed? true :timestamp now}
                 {:passed? false :timestamp now}]
                :7d))))

;; ---------------------------------------------------------------------------- SLI-6: Failure Distribution

(deftest failure-distribution-test
  (testing "correct distribution"
    (let [events [{:failure/class :failure.class/timeout :timestamp now}
                  {:failure/class :failure.class/timeout :timestamp now}
                  {:failure/class :failure.class/unknown :timestamp now}
                  {:failure/class :failure.class/external :timestamp now}]
          dist (rel/compute-failure-distribution events :7d)]
      (is (= 0.5 (get dist :failure.class/timeout)))
      (is (= 0.25 (get dist :failure.class/unknown)))
      (is (= 0.25 (get dist :failure.class/external)))))

  (testing "unknown rate extraction"
    (is (= 0.25 (rel/compute-unknown-failure-rate
                  [{:failure/class :failure.class/timeout :timestamp now}
                   {:failure/class :failure.class/timeout :timestamp now}
                   {:failure/class :failure.class/unknown :timestamp now}
                   {:failure/class :failure.class/external :timestamp now}]
                  :7d)))))

;; ---------------------------------------------------------------------------- SLO checking

(deftest slo-check-test
  (testing "SLI-1 below standard target is breach"
    (let [result (rel/check-slo {:sli/name :SLI-1 :sli/value 0.80 :sli/window :7d}
                                :standard)]
      (is (:breached? result))
      (is (= 0.85 (:slo/target result)))))

  (testing "SLI-1 above standard target is not breach"
    (let [result (rel/check-slo {:sli/name :SLI-1 :sli/value 0.90 :sli/window :7d}
                                :standard)]
      (is (not (:breached? result)))))

  (testing "SLI-6 inverted: above target is breach"
    (let [result (rel/check-slo {:sli/name :SLI-6 :sli/value 0.15 :sli/window :7d}
                                :standard)]
      (is (:breached? result))
      (is (= 0.10 (:slo/target result)))))

  (testing "advisory-only returns nil"
    (is (nil? (rel/check-slo {:sli/name :SLI-1 :sli/value 0.50 :sli/window :7d}
                             :best-effort)))))

;; ---------------------------------------------------------------------------- Error budgets

(deftest error-budget-test
  (testing "healthy budget"
    (let [b (rel/compute-error-budget 0.90 0.85 false)]
      (is (> (:error-budget/remaining b) 0.0))
      (is (not (rel/budget-exhausted? b)))
      (is (not (rel/budget-critical? b)))))

  (testing "exhausted budget"
    (let [b (rel/compute-error-budget 0.80 0.85 false)]
      (is (rel/budget-exhausted? b))))

  (testing "critical but not exhausted"
    (let [b (rel/compute-error-budget 0.855 0.85 false)]
      (is (not (rel/budget-exhausted? b)))
      ;; remaining ~0.033, which is < 0.25 threshold
      (is (rel/budget-critical? b))))

  (testing "inverted SLI budget"
    (let [b (rel/compute-error-budget 0.05 0.10 true)]
      (is (= 0.5 (:error-budget/remaining b)))
      (is (not (rel/budget-exhausted? b))))))

;; ---------------------------------------------------------------------------- compute-all-slis

(deftest compute-all-slis-test
  (testing "returns 7 SLI results"
    (let [results (rel/compute-all-slis {} :7d)]
      (is (= 7 (count results)))
      (is (every? #(contains? % :sli/name) results))
      (is (every? #(= :7d (:sli/window %)) results)))))

;; ---------------------------------------------------------------------------- Engine

(deftest engine-test
  (testing "compute-cycle! returns expected structure"
    (let [stream (atom {:events [] :subscribers {} :filters {} :sequence-numbers {} :sinks []})
          eng (rel/create-engine stream {:windows [:7d] :tiers [:standard]})
          metrics {:workflow-metrics [{:status :completed :timestamp now}
                                     {:status :completed :timestamp now}
                                     {:status :failed :timestamp now}]}
          result (rel/compute-cycle! eng metrics)]
      (is (vector? (:slis result)))
      (is (vector? (:slo-checks result)))
      (is (map? (:budgets result)))
      (is (contains? #{:nominal :degraded :safe-mode} (:recommendation result)))
      ;; Events should have been emitted
      (is (pos? (count (:events @stream)))))))
