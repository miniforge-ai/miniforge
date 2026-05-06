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

(ns ai.miniforge.agent.reviewer-test
  "Tests for the reviewer agent."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.reviewer :as reviewer]
   [ai.miniforge.agent.core :as core]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Regression-floor constants

(def ^:private min-stagnation-threshold-ms
  "Floor for the reviewer main-turn :stagnation-threshold-ms. Below this,
   Opus's pre-first-chunk think on heavy review prompts (8+ files,
   50–100k tokens) trips stagnation before the first structured-EDN
   chunk lands. This is the regression floor PR #783 establishes; a
   future drop below it would reintroduce the false-stagnation
   rejection observed on the 2026-05-04 dogfood."
  180000)

(def ^:private min-total-budget-ms
  "Floor for the reviewer main-turn :max-total-ms. Below this, long
   but legitimate reviews are killed mid-turn."
  600000)

;------------------------------------------------------------------------------ Test fixtures

(defn passing-gate
  "Create a gate that always passes."
  [gate-id]
  (loop/custom-gate gate-id :test
                    (fn [_artifact _context]
                      (loop/pass-result gate-id :test))))

(defn failing-gate
  "Create a gate that always fails with non-blocking errors."
  [gate-id error-message]
  (loop/custom-gate gate-id :test
                    (fn [_artifact _context]
                      (loop/fail-result gate-id :test
                                        [(assoc (loop/make-error :test-error error-message)
                                                :severity :non-blocking)]))))

(defn warning-gate
  "Create a gate that passes but emits warnings."
  [gate-id warning-message]
  (loop/custom-gate gate-id :test
                    (fn [_artifact _context]
                      (loop/pass-result gate-id :test
                                        :warnings [(loop/make-error :warning warning-message)]))))

(def sample-artifact
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content {:code/files [{:path "src/example.clj"
                                    :content "(ns example)\n(defn hello [] \"world\")"
                                    :action :create}]}})

;------------------------------------------------------------------------------ Core functionality tests

(deftest test-create-reviewer
  (testing "Create reviewer with default configuration"
    (let [reviewer (reviewer/create-reviewer)]
      (is (some? reviewer)
          "Should create reviewer instance")
      (is (= :reviewer (:role reviewer))
          "Role should be :reviewer")))

  (testing "Create reviewer with custom gates"
    (let [custom-gates [(passing-gate :gate1)
                        (passing-gate :gate2)]
          reviewer (reviewer/create-reviewer {:gates custom-gates})]
      (is (some? reviewer)
          "Should create reviewer with custom gates")))

  (testing "Create reviewer with strict mode"
    (let [reviewer (reviewer/create-reviewer {:strict true})]
      (is (some? reviewer)
          "Should create strict reviewer"))))

(deftest test-reviewer-invoke-all-pass
  (testing "Review with all gates passing"
    (let [gates [(passing-gate :gate1)
                 (passing-gate :gate2)
                 (passing-gate :gate3)]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)]

      (is (response/success? result)
          "Invocation should succeed")

      (let [review (:artifact result)]
        (is (= :approved (:review/decision review))
            "Should be approved when all gates pass")

        (is (= 3 (:review/gates-passed review))
            "Should have 3 passed gates")

        (is (= 0 (:review/gates-failed review))
            "Should have 0 failed gates")

        (is (= 3 (:review/gates-total review))
            "Should have 3 total gates")

        (is (empty? (:review/blocking-issues review))
            "Should have no blocking issues")

        (is (reviewer/approved? review)
            "approved? helper should return true")))))

(deftest test-reviewer-invoke-some-fail
  (testing "Review with some gates failing"
    (let [gates [(passing-gate :gate1)
                 (failing-gate :gate2 "Test failure")
                 (passing-gate :gate3)]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)]

      (is (response/success? result)
          "Invocation should succeed")

      (let [review (:artifact result)]
        (is (= :conditionally-approved (:review/decision review))
            "Should be conditionally approved by default")

        (is (= 2 (:review/gates-passed review))
            "Should have 2 passed gates")

        (is (= 1 (:review/gates-failed review))
            "Should have 1 failed gate")

        (is (not (reviewer/approved? review))
            "approved? should return false")

        (is (reviewer/conditionally-approved? review)
            "conditionally-approved? should return true")))))

(deftest test-reviewer-invoke-strict-mode
  (testing "Review with strict mode rejects on any failure"
    (let [gates [(passing-gate :gate1)
                 (failing-gate :gate2 "Test failure")]
          reviewer (reviewer/create-reviewer {:gates gates :strict true})
          result (core/invoke reviewer {} sample-artifact)
          review (:artifact result)]

      (is (= :rejected (:review/decision review))
          "Should be rejected in strict mode")

      (is (reviewer/rejected? review)
          "rejected? helper should return true")

      (is (seq (:review/blocking-issues review))
          "Should have blocking issues in strict mode"))))

(deftest test-reviewer-invoke-with-warnings
  (testing "Review with warnings but all passing"
    (let [gates [(passing-gate :gate1)
                 (warning-gate :gate2 "Minor issue")]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)
          review (:artifact result)]

      (is (= :approved (:review/decision review))
          "Should still be approved with warnings")

      (is (seq (:review/warnings review))
          "Should have warnings")

      (is (= 2 (:review/gates-passed review))
          "Both gates should pass"))))

(deftest test-reviewer-no-llm-usage
  (testing "Reviewer uses no tokens (no LLM)"
    (let [reviewer (reviewer/create-reviewer)
          result (core/invoke reviewer {} sample-artifact)]

      (is (= 0 (get-in result [:metrics :tokens]))
          "Should use 0 tokens - no LLM calls"))))

(deftest test-reviewer-rejects-unparseable-llm-output
  (testing "successful LLM calls that cannot be parsed fail closed"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             {:success? true
                              :content "not valid edn"
                              :tokens 42})
                  llm/success? :success?
                  llm/get-content :content]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)
            review (:artifact result)]
        (is (= :rejected (:review/decision review)))
        (is (= ["Reviewer LLM output could not be parsed into a review artifact"]
               (:review/blocking-issues review)))
        (is (= 42 (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-uses-parseable-content-even-when-backend-flags-failure
  (testing "parseable review content still drives the decision when backend success? is false"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             {:success? false
                              :content "```clojure\n{:review/decision :changes-requested\n :review/issues [{:severity :blocking :description \"Needs changes\"}]}\n```"
                              :tokens 7
                              :error {:message "artifact file not found"}})
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error :error]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)
            review (:artifact result)]
        (is (= :changes-requested (:review/decision review)))
        (is (some #{"Needs changes"} (:review/blocking-issues review)))
        (is (= 7 (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-timeout-only-parseable-failure-is-agent-error
  (testing "timeout-only parsed review failures do not become rejected code-review artifacts"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             {:success? false
                              :content "{:review/decision :rejected
                                         :review/gate-results [{:gate-id :unknown :gate-type :unknown :passed? true :errors [] :warnings [] :duration-ms 0}]
                                         :review/blocking-issues [\"Adaptive timeout: Stagnation timeout: no progress for 120183ms\"]
                                         :review/recommendations []}"
                              :tokens 9
                              :error {:message "Adaptive timeout: Stagnation timeout: no progress for 120183ms"}})
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error :error]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)]
        (is (= :error (:status result)))
        (is (= "Adaptive timeout: Stagnation timeout: no progress for 120183ms"
               (get-in result [:error :message])))
        (is (= :reviewer/backend-timeout
               (get-in result [:error :data :code])))
        (is (= 9 (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-timeout-only-parseable-success-wrapper-is-agent-error
  (testing "timeout-only parsed review failures are treated as backend errors even when the wrapper reports success"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             {:success? true
                              :content "{:review/decision :rejected
                                         :review/gate-results [{:gate-id :unknown :gate-type :unknown :passed? true :errors [] :warnings [] :duration-ms 0}
                                                                {:gate-id :unknown :gate-type :unknown :passed? true :errors [] :warnings [] :duration-ms 0}]
                                         :review/blocking-issues [\"Adaptive timeout: Stagnation timeout: no progress for 120228ms (type: stagnation, elapsed: 120228ms)\"]
                                         :review/recommendations []}"
                              :tokens 11})
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error (constantly nil)]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)]
        (is (= :error (:status result)))
        (is (= "Adaptive timeout: Stagnation timeout: no progress for 120228ms (type: stagnation, elapsed: 120228ms)"
               (get-in result [:error :message])))
        (is (= :reviewer/backend-timeout
               (get-in result [:error :data :code])))
        (is (= 11 (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-rejects-degraded-implement-handoff
  (testing "default reviewer rejects curated artifacts marked as degraded handoffs"
    (let [reviewer (reviewer/create-reviewer {:llm-backend nil})
          artifact {:code/id (random-uuid)
                    :code/files [{:path "src/example.clj"
                                  :content "(ns example)"
                                  :action :create}]
                    :code/degraded-handoff? true
                    :code/scope-deviations []}
          result (core/invoke reviewer {} artifact)
          review (:artifact result)]
      (is (= :rejected (:review/decision review)))
      (is (seq (:review/blocking-issues review))))))

(deftest test-reviewer-rejects-scope-deviations
  (testing "default reviewer rejects artifacts with curator-reported scope deviations"
    (let [reviewer (reviewer/create-reviewer {:llm-backend nil})
          artifact {:code/id (random-uuid)
                    :code/files [{:path "docs/out-of-scope.md"
                                  :content "oops"
                                  :action :modify}]
                    :code/scope-deviations ["docs/out-of-scope.md"]}
          result (core/invoke reviewer {} artifact)
          review (:artifact result)]
      (is (= :rejected (:review/decision review)))
      (is (some #(re-find #"out-of-scope" %) (:review/blocking-issues review))))))

;------------------------------------------------------------------------------ Schema validation tests

(deftest test-validate-review-artifact
  (testing "Validate valid review artifact"
    (let [review {:review/id (random-uuid)
                  :review/decision :approved
                  :review/gate-results []
                  :review/summary "All checks passed"
                  :review/gates-passed 3
                  :review/gates-failed 0
                  :review/gates-total 3}
          validation (reviewer/validate-review-artifact review)]
      (is (:valid? validation)
          "Valid review should pass validation")))

  (testing "Validate invalid review artifact - missing required fields"
    (let [review {:review/decision :approved}
          validation (reviewer/validate-review-artifact review)]
      (is (not (:valid? validation))
          "Review missing required fields should fail validation")))

  (testing "Validate invalid review artifact - gate counts don't add up"
    (let [review {:review/id (random-uuid)
                  :review/decision :approved
                  :review/gate-results []
                  :review/summary "Test"
                  :review/gates-passed 2
                  :review/gates-failed 0
                  :review/gates-total 5}  ; 2 + 0 ≠ 5
          validation (reviewer/validate-review-artifact review)]
      (is (not (:valid? validation))
          "Review with incorrect gate counts should fail validation"))))

;------------------------------------------------------------------------------ Helper function tests

(deftest test-review-summary
  (testing "Get review summary"
    (let [review {:review/id (random-uuid)
                  :review/decision :approved
                  :review/gates-passed 3
                  :review/gates-failed 0
                  :review/gates-total 3
                  :review/blocking-issues []
                  :review/warnings []}
          summary (reviewer/review-summary review)]
      (is (= :approved (:decision summary)))
      (is (= 3 (:gates-passed summary)))
      (is (= 0 (:gates-failed summary)))
      (is (= 0 (:blocking-issues-count summary)))
      (is (= 0 (:warnings-count summary))))))

(deftest test-decision-helpers
  (testing "Decision helper functions"
    (let [approved {:review/decision :approved}
          rejected {:review/decision :rejected}
          conditional {:review/decision :conditionally-approved}]

      (is (reviewer/approved? approved))
      (is (not (reviewer/approved? rejected)))
      (is (not (reviewer/approved? conditional)))

      (is (reviewer/rejected? rejected))
      (is (not (reviewer/rejected? approved)))
      (is (not (reviewer/rejected? conditional)))

      (is (reviewer/conditionally-approved? conditional))
      (is (not (reviewer/conditionally-approved? approved)))
      (is (not (reviewer/conditionally-approved? rejected))))))

(deftest test-get-blocking-issues
  (testing "Extract blocking issues"
    (let [review {:review/blocking-issues ["Issue 1" "Issue 2"]}]
      (is (= ["Issue 1" "Issue 2"] (reviewer/get-blocking-issues review)))
      (is (empty? (reviewer/get-blocking-issues {}))))))

(deftest test-get-warnings
  (testing "Extract warnings"
    (let [review {:review/warnings ["Warning 1" "Warning 2"]}]
      (is (= ["Warning 1" "Warning 2"] (reviewer/get-warnings review)))
      (is (empty? (reviewer/get-warnings {}))))))

(deftest test-get-recommendations
  (testing "Extract recommendations"
    (let [review {:review/recommendations ["Fix A" "Fix B"]}]
      (is (= ["Fix A" "Fix B"] (reviewer/get-recommendations review)))
      (is (empty? (reviewer/get-recommendations {}))))))

;------------------------------------------------------------------------------ Integration tests

(deftest test-reviewer-with-real-gates
  (testing "Review with syntax gate"
    (let [gates [(loop/syntax-gate)]
          reviewer (reviewer/create-reviewer {:gates gates})
          valid-artifact {:artifact/type :code
                          :artifact/content {:code/files [{:path "src/valid.clj"
                                                           :content "(ns valid)\n(defn f [] 1)"
                                                           :action :create}]}}
          result (core/invoke reviewer {} valid-artifact)]

      (is (response/success? result))
      ;; Note: actual gate behavior depends on loop implementation
      (is (some? (:artifact result))
          "Should return review artifact")))

  (testing "Review with multiple real gates"
    (let [gates [(loop/syntax-gate)
                 (loop/lint-gate)
                 (loop/policy-gate :security {:policies [:no-secrets]})]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)]

      (is (response/success? result))
      (is (= 3 (get-in result [:artifact :review/gates-total]))
          "Should run all 3 gates"))))

;------------------------------------------------------------------------------ Edge case tests

(deftest test-reviewer-with-no-gates
  (testing "Review with no gates should approve"
    (let [reviewer (reviewer/create-reviewer {:gates []})
          result (core/invoke reviewer {} sample-artifact)
          review (:artifact result)]

      (is (= :approved (:review/decision review))
          "Should approve when no gates configured")
      (is (= 0 (:review/gates-total review))))))

(deftest test-reviewer-with-gate-exception
  (testing "Review handles gate exceptions gracefully"
    (let [error-gate (loop/custom-gate :error-gate :test
                                        (fn [_artifact _context]
                                          (throw (Exception. "Gate crashed"))))
          reviewer (reviewer/create-reviewer {:gates [error-gate]})
          result (core/invoke reviewer {} sample-artifact)]

      (is (response/success? result)
          "Should succeed even when gate throws")

      (let [review (:artifact result)]
        ;; Gate exception should be captured and treated as failure
        (is (= 1 (:review/gates-failed review))
            "Exception should be treated as gate failure")))))

(deftest test-reviewer-metrics
  (testing "Reviewer returns proper metrics"
    (let [gates [(passing-gate :gate1)
                 (failing-gate :gate2 "fail")]
          reviewer (reviewer/create-reviewer {:gates gates})
          result (core/invoke reviewer {} sample-artifact)
          metrics (:metrics result)]

      (is (= 1 (:gates-passed metrics)))
      (is (= 1 (:gates-failed metrics)))
      (is (= 2 (:gates-total metrics)))
      (is (number? (:duration-ms metrics)))
      (is (= 0 (:tokens metrics))
          "Should report 0 tokens"))))

;------------------------------------------------------------------------------ Repair-loop progress detection

(def ^:private blocking-issue-a
  {:severity :blocking :file "src/foo.clj" :line 12 :description "Null pointer access"})

(def ^:private blocking-issue-b
  {:severity :blocking :file "src/bar.clj" :line 7  :description "Bad arity"})

(def ^:private warning-issue
  {:severity :warning :file "src/foo.clj" :line 90 :description "Inefficient loop"})

(def ^:private nit-issue
  {:severity :nit :file "src/foo.clj" :line 4 :description "Stale comment"})

(deftest review-fingerprint-includes-blocking-and-warning-test
  (testing "fingerprint covers actionable severities (:blocking + :warning)"
    (let [fp (reviewer/review-fingerprint
              {:review/issues [blocking-issue-a warning-issue]})]
      (is (= 2 (count fp)) ":blocking and :warning both flow through")
      (is (every? vector? fp) "each entry is a tuple"))))

(deftest review-fingerprint-excludes-nits-test
  (testing ":nit issues are intentionally excluded — long-tail polish, not stagnation"
    (let [fp (reviewer/review-fingerprint
              {:review/issues [blocking-issue-a nit-issue]})]
      (is (= 1 (count fp)) "only the blocking issue counted")
      (is (= :blocking (-> fp first first))))))

(deftest review-fingerprint-is-order-independent-test
  (testing "issue order does not affect the fingerprint"
    (let [fp1 (reviewer/review-fingerprint
               {:review/issues [blocking-issue-a blocking-issue-b]})
          fp2 (reviewer/review-fingerprint
               {:review/issues [blocking-issue-b blocking-issue-a]})]
      (is (= fp1 fp2)
          "fingerprint is sorted before comparison"))))

(deftest review-fingerprint-empty-when-no-actionable-issues-test
  (testing "approved review with only nits has empty fingerprint"
    (is (= [] (reviewer/review-fingerprint
               {:review/issues [nit-issue]})))
    (is (= [] (reviewer/review-fingerprint
               {:review/issues []})))))

(deftest review-fingerprint-discriminates-on-description-change-test
  (testing "fingerprint changes when an issue description changes"
    (let [original (reviewer/review-fingerprint
                    {:review/issues [blocking-issue-a]})
          edited   (reviewer/review-fingerprint
                    {:review/issues [(assoc blocking-issue-a
                                            :description "Different bug")]})]
      (is (not= original edited)
          "different description ⇒ different fingerprint"))))

(deftest review-fingerprint-survives-whitespace-reformatting-test
  (testing "trivial whitespace differences in the description don't read as progress"
    (let [base    (reviewer/review-fingerprint
                   {:review/issues [(assoc blocking-issue-a
                                           :description "Null pointer access")]})
          reflowed (reviewer/review-fingerprint
                    {:review/issues [(assoc blocking-issue-a
                                            :description "  Null   pointer\n access  ")]})]
      (is (= base reflowed)
          "whitespace normalization keeps the fingerprint stable across reformats"))))

(deftest review-fingerprint-includes-failed-gates-test
  (testing "gate-only mode: failed gate-results contribute to the fingerprint"
    ;; Critical for gate-only mode: the reviewer populates :review/gate-results
    ;; without filling :review/issues, so an :issues-only fingerprint would
    ;; be empty and the stagnation guard would never fire on a looping
    ;; gate failure. Failed gates must surface as virtual issues.
    (let [fp (reviewer/review-fingerprint
              {:review/gate-results [{:gate-id :syntax
                                      :passed? false
                                      :errors [{:message "Unbalanced paren at line 8"}]}
                                     {:gate-id :lint
                                      :passed? true
                                      :errors []}]})]
      (is (= 1 (count fp)) "only the failed gate contributes")
      (is (= :blocking (-> fp first first))
          "failed gates fingerprint as :blocking severity")
      (is (= ":gate/syntax" (-> fp first second))
          "gate id is the file slot for sortable identification"))))

(deftest stagnated?-true-on-identical-fingerprints-test
  (testing "two consecutive identical fingerprints with non-empty issues ⇒ stagnated"
    (let [fp (reviewer/review-fingerprint
              {:review/issues [blocking-issue-a]})]
      (is (true? (reviewer/stagnated? fp fp))))))

(deftest stagnated?-false-on-first-iteration-test
  (testing "no prior fingerprint ⇒ not stagnated (first review never short-circuits)"
    (let [fp (reviewer/review-fingerprint
              {:review/issues [blocking-issue-a]})]
      (is (false? (boolean (reviewer/stagnated? nil fp)))))))

(deftest stagnated?-false-on-empty-fingerprint-test
  (testing "no actionable issues ⇒ not stagnated regardless of prior — :approved is progress"
    (is (false? (boolean (reviewer/stagnated? [] []))))
    (is (false? (boolean (reviewer/stagnated?
                          (reviewer/review-fingerprint
                           {:review/issues [blocking-issue-a]})
                          []))))))

(deftest stagnated?-false-on-progress-test
  (testing "fingerprint changed between iterations ⇒ progress, not stagnated"
    (let [fp1 (reviewer/review-fingerprint
               {:review/issues [blocking-issue-a blocking-issue-b]})
          fp2 (reviewer/review-fingerprint
               {:review/issues [blocking-issue-b]})]
      (is (false? (boolean (reviewer/stagnated? fp1 fp2)))
          "one issue resolved between iterations is real progress"))))

(deftest reviewer-progress-monitor-thresholds-loaded-test
  ;; Guards the 2026-05-04 reviewer stagnation-threshold fix at the
  ;; reviewer boundary: a regression in prompt loading or
  ;; create-reviewer-progress-monitor would otherwise let the threshold
  ;; values silently drift back to the framework default 120s and
  ;; reintroduce the false-stagnation rejection that PR #783 fixes.
  ;; Mirrors planner-progress-monitor-thresholds-loaded-test in
  ;; planner_test.clj (Copilot review on PR #783 called for parity).
  (testing ":progress-monitor passed to LLM reflects reviewer.edn thresholds"
    (let [captured (atom nil)
          parseable-review (str "```clojure\n"
                                "{:review/decision :approved\n"
                                " :review/summary \"ok\"}\n"
                                "```")]
      (with-redefs [model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    llm/chat (fn [_client _prompt opts]
                               (reset! captured opts)
                               {:success? true
                                :content parseable-review
                                :tokens 1})
                    llm/chat-stream (fn [_client _prompt _on-chunk opts]
                                      (reset! captured opts)
                                      {:success? true
                                       :content parseable-review
                                       :tokens 1})
                    llm/success? :success?
                    llm/get-content :content]
        (let [reviewer (reviewer/create-reviewer
                        {:llm-backend ::mock-backend
                         :gates       []})]
          (core/invoke reviewer {} sample-artifact)
          (is (some? @captured) "LLM client should have been called")
          (let [monitor (:progress-monitor @captured)]
            (is (some? monitor)
                ":progress-monitor opt must reach the LLM client")
            (let [state @monitor]
              (is (>= (:stagnation-threshold-ms state)
                      min-stagnation-threshold-ms)
                  "Stagnation threshold must be ≥ min-stagnation-threshold-ms — Opus needs room for the pre-first-chunk think on heavy review prompts (8+ files, 50–100k tokens)")
              (is (>= (:max-total-ms state) min-total-budget-ms)
                  "Total budget must be ≥ min-total-budget-ms — covers heavy reviews"))))))))
