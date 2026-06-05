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

(ns ai.miniforge.agent.reviewer.gates-test
  "Tests for `ai.miniforge.agent.reviewer.gates` — extracted from
   `ai.miniforge.agent.reviewer-test` as part of the PR-D decomposition.

   Covers the deterministic gate path:
   - `gate-result->feedback` shape resolution (regression for the
     2026-05-18 :unknown-id dogfood incident).
   - Advisory vs blocking error classification (regression for the
     2026-05-24 :lint-flips-approval incident).
   - Decision merging when gates and LLM disagree.
   - Gate-feedback summaries + recommendations + counts."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.messages :as msg]
   [ai.miniforge.agent.reviewer.gates :as gates]
   [ai.miniforge.loop.interface :as loop]))

;------------------------------------------------------------------------------ Factories

(defn- failed-gate-feedback
  "Failed gate feedback with a given type and errors. Errors mirror
   loop/make-error (no :severity) unless a caller provides one."
  [gate-type errors]
  {:gate-id gate-type
   :gate-type gate-type
   :passed? false
   :errors errors
   :warnings []
   :duration-ms 0})

(defn- passed-gate-feedback
  "Passed gate feedback for summary/count tests."
  [gate-id gate-type]
  {:gate-id gate-id
   :gate-type gate-type
   :passed? true
   :errors []
   :warnings []
   :duration-ms 0})

;------------------------------------------------------------------------------ extract-blocking-issues + decision

(deftest advisory-gate-failure-does-not-block-approval-test
  ;; Regression: a failing :lint gate (errors carry no :severity, so the old
  ;; :blocking default classified them blocking) flipped a verify-passed,
  ;; LLM-:approved build to :rejected — capping every dogfood spec at the
  ;; :review-approved gate (2026-05-24, workflow adhoc-807481487).
  (testing "a :lint gate failure (no severity) is not a blocking issue"
    (let [failed [(failed-gate-feedback :lint [{:code :lint-error
                                                :message "trailing whitespace"}])]]
      (is (empty? (gates/extract-blocking-issues failed)))
      (is (= :conditionally-approved
             (:decision (gates/make-review-decision failed {})))
          "non-blocking failure downgrades to :conditionally-approved, not :rejected")))
  (testing "all non-advisory gates block by default (fail-safe), incl. :unknown"
    (doseq [gate-type [:syntax :policy :test :unknown]]
      (let [failed [(failed-gate-feedback gate-type [{:code :err :message "boom"}])]]
        (is (seq (gates/extract-blocking-issues failed))
            (str gate-type " failure must remain blocking"))
        (is (= :rejected (:decision (gates/make-review-decision failed {})))))))
  (testing "a gate-execution exception (create-exception-feedback) fails safe (blocking)"
    ;; create-exception-feedback yields {:gate-type :unknown :errors [{:type
    ;; :gate-exception ...}]} with no :severity — a crashed gate must block,
    ;; never approve open.
    (let [crashed (gates/create-exception-feedback
                   {} 0 (RuntimeException. "gate blew up") 1)]
      (is (false? (:passed? crashed)))
      (is (seq (gates/extract-blocking-issues [crashed]))
          "a crashed gate's error must be blocking")
      (is (= :rejected (:decision (gates/make-review-decision [crashed] {}))))))
  (testing "explicit :severity overrides gate-type classification"
    (is (seq (gates/extract-blocking-issues
              [(failed-gate-feedback :lint [{:message "x" :severity :blocking}])]))
        "an explicit :blocking severity blocks even on an advisory gate")
    (is (empty? (gates/extract-blocking-issues
                 [(failed-gate-feedback :policy [{:message "y" :severity :warning}])]))
        "an explicit :warning severity does not block even on a safety-net gate"))
  (testing "LLM :approved survives an advisory gate failure as :conditionally-approved (gate passes)"
    (let [gate-decision (:decision (gates/make-review-decision
                                    [(failed-gate-feedback :lint
                                                           [{:message "style"}])] {}))]
      (is (= :conditionally-approved
             (gates/merge-gate-overrides :approved gate-decision {}))))))

;------------------------------------------------------------------------------ gate-result->feedback resolution

(deftest gate-result-feedback-resolves-real-gate-ids-test
  ;; Coverage gap exposed by the 2026-05-18 dogfood: gate-result->feedback
  ;; was reading `:gate/id` off the gate defrecord (where SyntaxGate /
  ;; LintGate / CustomGate store `id` as a plain field), so every gate
  ;; surfaced as `:unknown` in the failing-gate-ids diagnostic. Pin
  ;; resolution from each shape so a regression here flips the test red
  ;; instead of silently degrading observability.
  (testing "CustomGate result: gate-id resolved from result's :gate/id"
    (let [gate (loop/custom-gate :my-custom :policy
                 (fn [_ _] (loop/pass-result :my-custom :policy)))
          result (loop/check-gate gate {} {})
          fb (gates/gate-result->feedback gate result)]
      (is (= :my-custom (:gate-id fb)))
      (is (= :policy (:gate-type fb)))
      (is (true? (:passed? fb)))))

  (testing "CustomGate result with explicit :gate/id wins over the gate's :id"
    (let [gate (loop/custom-gate :gate-side :policy (fn [_ _] {}))
          result {:gate/id :result-side :gate/type :other :gate/passed? false :gate/errors []}
          fb (gates/gate-result->feedback gate result)]
      (is (= :result-side (:gate-id fb))
          "result's :gate/id always wins — pass-result/fail-result are authoritative")
      (is (= :other (:gate-type fb)))))

  (testing "CustomGate result without :gate/id falls back to the record's :id"
    (let [gate (loop/custom-gate :on-the-record :policy (fn [_ _] {}))
          result {:gate/passed? false :gate/errors [{:message "boom"}]}
          fb (gates/gate-result->feedback gate result)]
      (is (= :on-the-record (:gate-id fb))
          ":id from the defrecord must surface when the result is bare")
      (is (= :policy (:gate-type fb))
          ":type-kw from the CustomGate record must surface when the result is bare")))

  (testing "SyntaxGate result resolves to a real id even though the record has no :type-kw field"
    (let [gate (loop/syntax-gate)
          result {:gate/id :syntax :gate/type :syntax :gate/passed? true}
          fb (gates/gate-result->feedback gate result)]
      (is (= :syntax (:gate-id fb))
          "syntax gate must NOT show as :unknown — it was the canonical regression")
      (is (= :syntax (:gate-type fb)))))

  (testing "missing-id path falls back to :unknown — keeps the existing safety net"
    (let [gate {:not-a-record true}    ; non-record map with no id at all
          result {:gate/passed? false :gate/errors []}
          fb (gates/gate-result->feedback gate result)]
      (is (= :unknown (:gate-id fb)))
      (is (= :unknown (:gate-type fb))))))

;------------------------------------------------------------------------------ Summary + recommendations + counts

(deftest calculate-gate-counts-test
  (let [fbs [(passed-gate-feedback :a :syntax)
             (passed-gate-feedback :b :test)
             (failed-gate-feedback :c [{:message "x"}])]]
    (is (= {:passed 2 :failed 1 :total 3}
           (gates/calculate-gate-counts fbs)))))

(deftest generate-summary-localized-test
  ;; All four decision branches go through the agent message catalog
  ;; (PR-D folded the raw English `(format ...)` strings into
  ;; :reviewer.gates/summary-* keys).
  (let [fbs [(passed-gate-feedback :a :syntax)
             (passed-gate-feedback :b :test)
             (failed-gate-feedback :c [{:message "x"}])]]
    (is (= (msg/t :reviewer.gates/summary-approved {:total 3})
           (gates/generate-summary :approved fbs)))
    (is (= (msg/t :reviewer.gates/summary-rejected {:failed 1 :total 3})
           (gates/generate-summary :rejected fbs)))
    (is (= (msg/t :reviewer.gates/summary-conditionally-approved
                  {:passed 2 :total 3 :failed 1})
           (gates/generate-summary :conditionally-approved fbs)))
    (is (= (msg/t :reviewer.gates/summary-default
                  {:decision "changes-requested" :total 3})
           (gates/generate-summary :changes-requested fbs)))))

(deftest generate-recommendations-test
  (let [fbs [(passed-gate-feedback :ok :syntax)
             (failed-gate-feedback :lint [{:message "trailing ws"
                                           :fix-suggestion "run cljfmt"}])
             (failed-gate-feedback :policy [{:message "no secrets in code"}])]
        recs (gates/generate-recommendations fbs)]
    (is (= 2 (count recs))
        "passed feedbacks contribute no recommendations")
    (is (some #(re-find #"lint" %) recs))
    (is (some #(re-find #"-> run cljfmt" %) recs)
        "fix-suggestion is included after a `->`")
    (is (some #(re-find #"policy" %) recs))
    (is (not-any? #(re-find #"->" %) (filter #(re-find #"policy" %) recs))
        "no `->` when the error has no :fix-suggestion")))

;------------------------------------------------------------------------------ Localized custom-gate errors

(deftest implementation-handoff-gate-localizes-errors-test
  ;; Both degraded-handoff and scope-deviation messages go through the
  ;; catalog now. Pin the catalog text so a raw-string regression in this
  ;; gate flips the test red.
  (let [gate (gates/implementation-handoff-gate)]
    (testing "degraded handoff fails with the localized message"
      (let [result (loop/check-gate gate
                                    {:code/degraded-handoff? true}
                                    {})]
        (is (false? (:gate/passed? result)))
        (is (= (msg/t :reviewer.gates/degraded-handoff)
               (-> result :gate/errors first :message)))))
    (testing "scope deviations fail with the localized message and joined paths"
      (let [result (loop/check-gate gate
                                    {:code/scope-deviations ["a.clj" "b.clj"]}
                                    {})]
        (is (false? (:gate/passed? result)))
        (is (= (msg/t :reviewer.gates/scope-deviations {:paths "a.clj, b.clj"})
               (-> result :gate/errors first :message)))))))
