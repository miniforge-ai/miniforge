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

(ns ai.miniforge.gate.behavioral-test
  "Tests for the :behavioral gate.

   Covers:
   - check-behavioral with empty events / no packs → passes with warning
   - check-behavioral with events and no violations → passes
   - check-behavioral with events triggering blocking violations → fails with errors
   - check-behavioral with events triggering warning-level violations → passes with warnings
   - repair-behavioral always returns {:success? false}
   - Gate registered and retrievable via gate/get-gate"
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.gate.behavioral :as behavioral]
   [ai.miniforge.gate.interface :as gate]))

;; ============================================================================
;; Fixture data
;; ============================================================================

(def ^:private sample-events
  [{:event/type :agent/started  :event/data {}}
   {:event/type :agent/completed :event/data {:exit-code 0}}])

(def ^:private blocking-violation
  {:violation/severity :critical
   :violation/rule-id  :no-crash
   :violation/message  "Agent crashed unexpectedly"})

(def ^:private error-violation
  {:violation/severity :error
   :violation/rule-id  :phase-failed
   :violation/message  "Implement phase returned non-zero exit"})

(def ^:private warning-violation
  {:violation/severity :warning
   :violation/rule-id  :slow-phase
   :violation/message  "Phase took longer than configured threshold"})

(def ^:private info-violation
  {:violation/severity :info
   :violation/rule-id  :coverage-note
   :violation/message  "Coverage slightly below recommended level"})

;; ============================================================================
;; check-behavioral — empty events + no policy packs
;; ============================================================================

(deftest check-behavioral-empty-no-packs-passes-with-warning-test
  (testing "passes with warning when event list is empty and no policy packs"
    (let [result (behavioral/check-behavioral {} {})]
      (is (:passed? result))
      (is (seq (:warnings result)) "should carry at least one warning")
      (is (= :no-behavioral-harness
             (get-in (first (:warnings result)) [:type]))
          "warning type should be :no-behavioral-harness")))

  (testing "explicit empty :events key also triggers the no-harness warning"
    (let [result (behavioral/check-behavioral {:events []} {})]
      (is (:passed? result))
      (is (= :no-behavioral-harness
             (get-in (first (:warnings result)) [:type]))))))

;; ============================================================================
;; check-behavioral — events with no violations
;; ============================================================================

(deftest check-behavioral-events-no-violations-passes-test
  (testing "passes cleanly when pre-computed violations list is empty"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations []})]
      (is (:passed? result))
      (is (empty? (:errors result)))
      (is (empty? (:warnings result)))))

  (testing "passes with no errors when events present but no packs loaded"
    ;; No :policy-packs and no :behavioral-violations in ctx.
    ;; derive-violations falls back to [] when policy-pack component is absent.
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations []})]
      (is (:passed? result))
      (is (nil? (seq (:errors result)))))))

;; ============================================================================
;; check-behavioral — events triggering blocking violations
;; ============================================================================

(deftest check-behavioral-blocking-violation-fails-test
  (testing "fails when a :critical violation is present"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [blocking-violation]})]
      (is (false? (:passed? result)))
      (is (seq (:errors result)))
      (is (= :behavioral-violation
             (get-in (first (:errors result)) [:type])))
      (is (= :critical
             (get-in (first (:errors result)) [:severity])))))

  (testing "fails when a :error-severity violation is present"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [error-violation]})]
      (is (false? (:passed? result)))
      (is (= :behavioral-violation
             (get-in (first (:errors result)) [:type])))))

  (testing "error entry carries rule-id when violation provides one"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [blocking-violation]})]
      (is (= :no-crash
             (get-in (first (:errors result)) [:rule-id])))))

  (testing "error entry carries violation message"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [blocking-violation]})]
      (is (= "Agent crashed unexpectedly"
             (get-in (first (:errors result)) [:message])))))

  (testing "multiple blocking violations all appear in :errors"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [blocking-violation error-violation]})]
      (is (false? (:passed? result)))
      (is (= 2 (count (:errors result))))))

  (testing "warnings collected alongside blocking violations"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [blocking-violation warning-violation]})]
      (is (false? (:passed? result)))
      (is (= 1 (count (:errors result))))
      (is (= 1 (count (:warnings result)))))))

;; ============================================================================
;; check-behavioral — events triggering warning-level violations
;; ============================================================================

(deftest check-behavioral-warning-violations-passes-test
  (testing "passes when only :warning violations are present"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [warning-violation]})]
      (is (:passed? result))
      (is (empty? (:errors result)))
      (is (seq (:warnings result)))))

  (testing "warning entry type is :behavioral-warning"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [warning-violation]})]
      (is (= :behavioral-warning
             (get-in (first (:warnings result)) [:type])))))

  (testing "warning entry carries rule-id when violation provides one"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [warning-violation]})]
      (is (= :slow-phase
             (get-in (first (:warnings result)) [:rule-id])))))

  (testing "passes when only :info violations are present"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [info-violation]})]
      (is (:passed? result))
      (is (empty? (:errors result)))
      (is (seq (:warnings result)))))

  (testing "multiple warning violations all appear in :warnings"
    (let [result (behavioral/check-behavioral
                   {:events sample-events}
                   {:behavioral-violations [warning-violation info-violation]})]
      (is (:passed? result))
      (is (= 2 (count (:warnings result)))))))

;; ============================================================================
;; repair-behavioral
;; ============================================================================

(deftest repair-behavioral-always-fails-test
  (testing "returns {:success? false} for empty artifact"
    (let [result (behavioral/repair-behavioral {} [] {})]
      (is (false? (:success? result)))))

  (testing "returns {:success? false} regardless of errors passed"
    (let [result (behavioral/repair-behavioral
                   {:events sample-events}
                   [{:type :behavioral-violation :message "crash"}]
                   {})]
      (is (false? (:success? result)))))

  (testing "echoes the original artifact unchanged"
    (let [art    {:events sample-events :run/id "abc"}
          result (behavioral/repair-behavioral art [] {})]
      (is (= art (:artifact result)))))

  (testing "includes a human-readable message"
    (let [result (behavioral/repair-behavioral {} [] {})]
      (is (string? (:message result)))
      (is (pos? (count (:message result)))))))

;; ============================================================================
;; Registry — gate/get-gate
;; ============================================================================

(deftest gate-registered-and-retrievable-test
  (testing "gate is registered and retrievable via gate/get-gate"
    (let [g (gate/get-gate :behavioral)]
      (is (map? g))
      (is (= :behavioral (:name g)))
      (is (fn? (:check g)))
      (is (fn? (:repair g)))))

  (testing "retrieved check fn delegates to check-behavioral"
    (let [check-fn (:check (gate/get-gate :behavioral))
          result   (check-fn {} {})]
      (is (:passed? result))
      (is (= :no-behavioral-harness
             (get-in (first (:warnings result)) [:type])))))

  (testing "retrieved repair fn delegates to repair-behavioral"
    (let [repair-fn (:repair (gate/get-gate :behavioral))
          result    (repair-fn {} [] {})]
      (is (false? (:success? result))))))

;; ============================================================================
;; classify-violations (public helper — low-level unit tests)
;; ============================================================================

(deftest classify-violations-test
  (testing "partitions :critical into :blocking"
    (let [{:keys [blocking]} (behavioral/classify-violations
                               [{:violation/severity :critical}])]
      (is (= 1 (count blocking)))))

  (testing "partitions :error into :blocking"
    (let [{:keys [blocking]} (behavioral/classify-violations
                               [{:violation/severity :error}])]
      (is (= 1 (count blocking)))))

  (testing "partitions :warning into :warnings"
    (let [{:keys [warnings]} (behavioral/classify-violations
                               [{:violation/severity :warning}])]
      (is (= 1 (count warnings)))))

  (testing "partitions :info into :warnings"
    (let [{:keys [warnings]} (behavioral/classify-violations
                               [{:violation/severity :info}])]
      (is (= 1 (count warnings)))))

  (testing "returns empty buckets for empty input"
    (let [{:keys [blocking warnings]} (behavioral/classify-violations [])]
      (is (empty? blocking))
      (is (empty? warnings))))

  (testing "unknown severity falls into neither bucket"
    (let [{:keys [blocking warnings]} (behavioral/classify-violations
                                        [{:violation/severity :unknown}])]
      (is (empty? blocking))
      (is (empty? warnings)))))
