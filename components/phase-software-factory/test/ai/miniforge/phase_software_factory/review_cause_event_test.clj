;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.phase-software-factory.review-cause-event-test
  "Tests for :review/cause enrichment on terminal review verdicts.

   Covers two surfaces:
     1. build-review-cause (pure helper, tested via #') — cause map shape per verdict
     2. leave-review integration — :review/cause rides on emit-phase-completed! payload"
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.phase-software-factory.review]
   [ai.miniforge.phase-software-factory.implement]))

;; ---------------------------------------------------------------------------
;; Layer 0 — factories and shared constants

(def ^:private phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource phase-test-config-resource]
        (f))
      (finally
        (phase/reset-phase-loader!)))))

;; Private helper accessed directly for pure-function unit tests.
(def ^:private build-review-cause
  #'ai.miniforge.phase-software-factory.review-convergence/build-review-cause)

(def ^:private blocking-cause-map
  {:cause/type :blocking-defect :warning-only-count 0})

(def ^:private warning-cause-map
  {:cause/type :warning-churn :warning-only-count 2})

(defn- make-leave-review-ctx
  "Minimal context for leave-review, matching the shape used in
   review-repair-loop-test."
  [decision iterations max-iterations review-output]
  (let [result {:output  (merge {:review/decision decision} review-output)
                :metrics {:tokens 100 :duration-ms 5000}}]
    {:phase           {:started-at (- (System/currentTimeMillis) 1000)
                       :iterations iterations
                       :budget     {:iterations max-iterations}
                       :result     result}
     :phase-config    {:phase :review}
     :execution/phase-results {}
     :execution/input {:description "test task"}
     :execution/metrics {}}))

(defn- run-leave-review
  "Invoke the leave-review interceptor function and return the updated context."
  [ctx]
  (let [interceptor (phase/get-phase-interceptor {:phase :review})
        leave-fn    (:leave interceptor)]
    (leave-fn ctx)))

;; ---------------------------------------------------------------------------
;; Layer 1 — build-review-cause unit tests (pure function, no event-stream)

(deftest build-review-cause-returns-nil-for-non-terminal-verdicts
  (testing "non-terminal verdicts produce no cause map"
    (doseq [v [:repair-requested :approved :review/backend-timeout]]
      (is (nil? (build-review-cause v blocking-cause-map 0 1 {}))
          (str "verdict " v " should not produce a cause map")))))

(deftest build-review-cause-stagnated
  (testing "stagnated verdict — correct shape, no unresolved-warning-count"
    (let [cause (build-review-cause :stagnated blocking-cause-map 0 3 {})]
      (is (= :stagnated       (:review/verdict cause)))
      (is (= :blocking-defect (:cause/type cause)))
      (is (= 0 (:review/warning-only-cycles cause)))
      (is (= 3 (:review/total-cycles cause)))
      (is (not (contains? cause :review/unresolved-warning-count))
          "stagnated should not carry unresolved-warning-count"))))

(deftest build-review-cause-needs-decomposition
  (testing "needs-decomposition verdict — correct shape, no unresolved-warning-count"
    (let [cause (build-review-cause :needs-decomposition blocking-cause-map 1 4 {})]
      (is (= :needs-decomposition (:review/verdict cause)))
      (is (= :blocking-defect     (:cause/type cause)))
      (is (= 1 (:review/warning-only-cycles cause)))
      (is (= 4 (:review/total-cycles cause)))
      (is (not (contains? cause :review/unresolved-warning-count))
          "needs-decomposition should not carry unresolved-warning-count"))))

(deftest build-review-cause-exhausted
  (testing "exhausted verdict — correct shape, no unresolved-warning-count"
    (let [cause (build-review-cause :exhausted blocking-cause-map 0 2 {})]
      (is (= :exhausted       (:review/verdict cause)))
      (is (= :blocking-defect (:cause/type cause)))
      (is (= 0 (:review/warning-only-cycles cause)))
      (is (= 2 (:review/total-cycles cause)))
      (is (not (contains? cause :review/unresolved-warning-count))
          "exhausted should not carry unresolved-warning-count"))))

(deftest build-review-cause-accept-with-warnings-includes-count
  (testing "accept-with-warnings — includes :review/unresolved-warning-count"
    (let [warnings [{:text "nit: rename x to foo"}
                    {:text "nit: add docstring"}]
          artifact {:review/warnings warnings}
          cause    (build-review-cause :accept-with-warnings warning-cause-map 2 3 artifact)]
      (is (= :accept-with-warnings (:review/verdict cause)))
      (is (= :warning-churn        (:cause/type cause)))
      (is (= 2 (:review/warning-only-cycles cause)))
      (is (= 3 (:review/total-cycles cause)))
      (is (= 2 (:review/unresolved-warning-count cause))
          "should count the warnings in the review artifact"))))

(deftest build-review-cause-accept-with-warnings-no-warnings
  (testing "accept-with-warnings with no warnings in artifact → count is 0"
    (let [cause (build-review-cause :accept-with-warnings warning-cause-map 2 2 {})]
      (is (= 0 (:review/unresolved-warning-count cause))
          "absent :review/warnings → count 0, not nil or exception"))))

(deftest build-review-cause-defaults-to-blocking-defect-when-cause-map-nil
  (testing "nil cause-map falls back to :blocking-defect"
    (let [cause (build-review-cause :exhausted nil 0 1 {})]
      (is (= :blocking-defect (:cause/type cause))
          "absent cause-map → :blocking-defect default"))))

;; ---------------------------------------------------------------------------
;; Layer 2 — integration tests via emit-phase-completed! capture

(deftest exhausted-verdict-emits-review-cause-in-phase-completed
  (testing "exhausted: :review/cause appears in emit-phase-completed! payload"
    (let [captured (atom nil)
          ctx      (make-leave-review-ctx :rejected 4 4 {})]
      (with-redefs [phase/emit-phase-completed!
                    (fn [_ctx _phase-kw payload]
                      (reset! captured payload))]
        (run-leave-review ctx)
        (is (some? (:review/cause @captured))
            "exhausted verdict must include :review/cause")
        (is (= :exhausted
               (get-in @captured [:review/cause :review/verdict])))
        (is (= :blocking-defect
               (get-in @captured [:review/cause :cause/type])))
        (is (= 1
               (get-in @captured [:review/cause :review/total-cycles]))
            "first review cycle → total-cycles = 1")))))

(deftest approved-verdict-does-not-emit-review-cause
  (testing "approved: :review/cause is absent from emit-phase-completed! payload"
    (let [captured (atom nil)
          ctx      (make-leave-review-ctx :approved 1 4 {})]
      (with-redefs [phase/emit-phase-completed!
                    (fn [_ctx _phase-kw payload]
                      (reset! captured payload))]
        (run-leave-review ctx)
        (is (nil? (:review/cause @captured))
            "approved verdict must not include :review/cause")))))

(deftest repair-requested-does-not-emit-review-cause
  (testing "repair-requested (within budget): :review/cause is absent from payload"
    (let [captured (atom nil)
          ctx      (make-leave-review-ctx
                    :rejected 1 4
                    {:review/issues [{:severity :blocking :description "Use defn not def"}]})]
      (with-redefs [phase/emit-phase-completed!
                    (fn [_ctx _phase-kw payload]
                      (reset! captured payload))]
        (run-leave-review ctx)
        (is (nil? (:review/cause @captured))
            "repair-requested is not a terminal verdict; no :review/cause")))))

(comment
  ;; REPL: run individual cause map assertions
  (build-review-cause :stagnated blocking-cause-map 0 3 {})
  (build-review-cause :accept-with-warnings warning-cause-map 2 3
                      {:review/warnings [{:text "nit: foo"}]}))
