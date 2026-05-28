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
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.messages.interface :as messages]
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

(def ^:private unparseable-output-token-count
  42)

(def ^:private parseable-backend-failure-token-count
  7)

(def ^:private timeout-review-token-count
  9)

(def ^:private timeout-success-wrapper-token-count
  11)

(def ^:private backend-timeout-elapsed-ms
  120183)

(def ^:private wrapped-timeout-elapsed-ms
  120228)

(def ^:private backend-timeout-stop-reason
  "timeout")

(def ^:private backend-timeout-num-turns
  4)

(def ^:private backend-timeout-type
  "adaptive_timeout")

(def ^:private test-t
  (messages/create-translator "config/agent/test-fixtures/en-US.edn"
                              :agent-reviewer-test/fixtures))

(def ^:private valid-review-blocking-description
  (test-t :reviewer-test/valid-review-blocking-description))

(def ^:private valid-review-content
  (test-t :reviewer-test/valid-review-content))

(def ^:private malformed-review-issue-content
  (test-t :reviewer-test/malformed-review-issue-content))

(def ^:private approved-with-nil-line-content
  (test-t :reviewer-test/approved-with-nil-line-content))

(def ^:private approved-with-nil-optional-fields-content
  (test-t :reviewer-test/approved-with-nil-optional-fields-content))

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

(defn- review-gate-feedback
  ([] (review-gate-feedback :unknown 0))
  ([gate-id duration-ms]
   {:gate-id gate-id
    :gate-type :unknown
    :passed? true
    :errors []
    :warnings []
    :duration-ms duration-ms}))

(defn- adaptive-timeout-message
  [elapsed-ms]
  (str "Adaptive timeout: Stagnation timeout: no progress for "
       elapsed-ms
       "ms"))

(defn- wrapped-timeout-message
  [elapsed-ms]
  (str (adaptive-timeout-message elapsed-ms)
       " (type: stagnation, elapsed: "
       elapsed-ms
       "ms)"))

(defn- timeout-only-review-content
  ([blocking-message]
   (timeout-only-review-content blocking-message [(review-gate-feedback)]))
  ([blocking-message gate-results]
   (pr-str {:review/decision :rejected
            :review/gate-results gate-results
            :review/blocking-issues [blocking-message]
            :review/recommendations []})))

(defn- mock-llm-response
  [content & {:as extra}]
  (merge {:success? true
          :content content
          :tokens 1}
         extra))

(defn- backend-timeout-error
  ([message]
   (backend-timeout-error message nil))
  ([message data]
   (cond-> {:type backend-timeout-type
            :message message}
     data (assoc :data data))))

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
                             (mock-llm-response
                              "not valid edn"
                              :tokens unparseable-output-token-count))
                  llm/success? :success?
                  llm/get-content :content]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)
            review (:artifact result)]
        (is (= :rejected (:review/decision review)))
        (is (= ["Reviewer LLM output could not be parsed into a review artifact"]
               (:review/blocking-issues review)))
        (is (= unparseable-output-token-count
               (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-uses-parseable-content-even-when-backend-flags-failure
  (testing "parseable review content still drives the decision when backend success? is false"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response
                              valid-review-content
                              :success? false
                              :tokens parseable-backend-failure-token-count
                              :error {:message "artifact file not found"}))
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error :error]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)
            review (:artifact result)]
        (is (= :changes-requested (:review/decision review)))
        (is (some #{valid-review-blocking-description} (:review/blocking-issues review)))
        (is (= parseable-backend-failure-token-count
               (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-rejects-structurally-corrupt-llm-issues
  (testing "valid EDN with malformed issue maps is treated as unparseable"
    (is (nil? (reviewer/parse-review-response malformed-review-issue-content)))))

(deftest test-reviewer-accepts-issues-with-nil-line
  ;; Reproduces the 2026-05-16 event-log-tool-visibility dogfood
  ;; gate-vs-verdict mismatch: the LLM emitted :line nil on a
  ;; file-level issue. The schema's pre-fix shape
  ;; (`{:optional true} [:int {:min 1}]`) silently rejected the issue,
  ;; parser returned nil, parse-failed? flipped llm-decision to
  ;; :rejected, and the :review-approved gate correctly rejected a
  ;; decision that should have been :approved. With :line wrapped in
  ;; :maybe, explicit nil now round-trips through the parser.
  (testing "issue with :line nil parses with :review/decision intact"
    (let [parsed (reviewer/parse-review-response approved-with-nil-line-content)]
      (is (some? parsed) "parser must not return nil for a nil-line issue")
      (is (= :approved (:review/decision parsed)))
      (is (= 2 (count (:review/issues parsed))))
      (is (nil? (:line (first (:review/issues parsed))))
          ":line nil must round-trip through the parser, not get dropped"))))

(deftest test-reviewer-accepts-issues-with-all-optional-fields-nil
  (testing "issue with :file nil, :line nil, :suggestion nil parses cleanly"
    (let [parsed (reviewer/parse-review-response approved-with-nil-optional-fields-content)]
      (is (some? parsed))
      (is (= :approved (:review/decision parsed))))))

(deftest test-reviewer-timeout-only-parseable-failure-is-agent-error
  (testing "timeout-only parsed review failures do not become rejected code-review artifacts"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response
                              (timeout-only-review-content
                               (adaptive-timeout-message backend-timeout-elapsed-ms))
                              :success? false
                              :tokens timeout-review-token-count
                              :error {:message (adaptive-timeout-message backend-timeout-elapsed-ms)}))
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error :error]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)]
        (is (= :error (:status result)))
        (is (= (adaptive-timeout-message backend-timeout-elapsed-ms)
               (get-in result [:error :message])))
        (is (= :reviewer/backend-timeout
               (get-in result [:error :data :code])))
        (is (= timeout-review-token-count
               (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-timeout-only-parseable-success-wrapper-is-agent-error
  (testing "timeout-only parsed review failures are treated as backend errors even when the wrapper reports success"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response
                              (timeout-only-review-content
                               (wrapped-timeout-message wrapped-timeout-elapsed-ms)
                               [(review-gate-feedback)
                                (review-gate-feedback)])
                              :success? true
                              :tokens timeout-success-wrapper-token-count))
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error (constantly nil)]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)]
        (is (= :error (:status result)))
        (is (= (wrapped-timeout-message wrapped-timeout-elapsed-ms)
               (get-in result [:error :message])))
        (is (= :reviewer/backend-timeout
               (get-in result [:error :data :code])))
        (is (= timeout-success-wrapper-token-count
               (get-in result [:metrics :tokens])))))))

(deftest test-reviewer-timeout-only-shape-does-not-hide-real-gate-failures
  (testing "timeout-only classification requires the deterministic gates to approve"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response
                              (timeout-only-review-content
                               (adaptive-timeout-message backend-timeout-elapsed-ms)
                               [])
                              :success? false
                              :tokens timeout-review-token-count
                              :error (backend-timeout-error
                                      (adaptive-timeout-message backend-timeout-elapsed-ms))))
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error :error]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates [(failing-gate :gate1 "Gate failure")]})
            result (core/invoke reviewer {} sample-artifact)]
        (is (= :success (:status result)))
        (is (not= :reviewer/backend-timeout
                  (get-in result [:error :data :code])))
        (is (= :rejected (get-in result [:artifact :review/decision])))))))

(deftest test-reviewer-timeout-only-error-emits-phase-completed
  (testing "timeout-only backend errors still emit review phase completion telemetry"
    (let [events (atom [])]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                    llm/chat (fn [_client _prompt _opts]
                               (mock-llm-response
                                (timeout-only-review-content
                                 (adaptive-timeout-message backend-timeout-elapsed-ms))
                                :success? false
                                :tokens timeout-review-token-count
                                :error (backend-timeout-error
                                        (adaptive-timeout-message backend-timeout-elapsed-ms)
                                        {:elapsed-ms backend-timeout-elapsed-ms})))
                    llm/success? :success?
                    llm/get-content :content
                    llm/get-error :error
                    log/info (fn [_logger category event payload]
                               (swap! events conj {:category category
                                                   :event event
                                                   :payload payload}))]
        (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                  :gates []})
              result (core/invoke reviewer {} sample-artifact)
              completed-event (some #(when (= :reviewer/phase-completed (:event %)) %) @events)]
          (is (= :error (:status result)))
          (is completed-event)
          (is (= :reviewer/backend-timeout
                 (get-in completed-event [:payload :data :error-code])))
          (is (= :error
                 (get-in completed-event [:payload :data :status]))))))))

(deftest test-reviewer-timeout-only-error-preserves-backend-metadata
  (testing "timeout-only backend errors preserve normalized backend metadata for post-mortem"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response
                              (timeout-only-review-content
                               (adaptive-timeout-message backend-timeout-elapsed-ms))
                              :success? false
                              :tokens timeout-review-token-count
                              :stop-reason backend-timeout-stop-reason
                              :num-turns backend-timeout-num-turns
                              :error (backend-timeout-error
                                      (adaptive-timeout-message backend-timeout-elapsed-ms)
                                      {:elapsed-ms backend-timeout-elapsed-ms})))
                  llm/success? :success?
                  llm/get-content :content
                  llm/get-error :error]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            result (core/invoke reviewer {} sample-artifact)]
        (is (= :error (:status result)))
        (is (= :reviewer/backend-timeout
               (get-in result [:error :data :code])))
        (is (= backend-timeout-type
               (get-in result [:error :data :type])))
        (is (= backend-timeout-elapsed-ms
               (get-in result [:error :data :elapsed-ms])))
        (is (= backend-timeout-stop-reason
               (get-in result [:error :data :stop-reason])))
        (is (= backend-timeout-num-turns
               (get-in result [:error :data :num-turns])))))))

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

;; ============================================================================
;; LLM vs gate disagreement — observability for the 2026-05-18 dogfood
;; finding (LLM :approved silently overridden by failing internal gates)
;; ============================================================================

(deftest test-reviewer-emits-gate-overrode-llm-warn-on-disagreement
  ;; When the LLM emits :approved but a deterministic gate fails, the
  ;; final decision flips and the workflow gate fails with no signal
  ;; to the operator about which internal gate caused the override.
  ;; Pin the diagnostic log + the :failing-gate-ids / :gate-overrode-llm?
  ;; fields on :reviewer/review-complete, plus the dedicated
  ;; :reviewer/gate-overrode-llm warn entry.
  (testing ":reviewer/gate-overrode-llm warn fires when LLM :approved becomes final :rejected"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response
                               "```clojure
{:review/decision :approved
 :review/issues []
 :review/summary \"LGTM\"}
```"))
                  llm/success? :success?
                  llm/get-content :content]
      (let [[logger entries] (log/collecting-logger {:min-level :trace})
            failing-gate (loop/custom-gate
                           :always-fails
                           :policy
                           (fn [_artifact _ctx]
                             (loop/fail-result :always-fails :policy
                                               [(loop/make-error :failed
                                                                 "deterministic gate")])))
            reviewer (reviewer/create-reviewer
                       {:llm-backend ::mock-backend
                        :gates [failing-gate]
                        :logger logger})
            result (core/invoke reviewer {} sample-artifact)
            complete-entry (some #(when (= :reviewer/review-complete (:log/event %)) %)
                                 @entries)
            override-entry (some #(when (= :reviewer/gate-overrode-llm (:log/event %)) %)
                                 @entries)]
        (is (= :rejected (:review/decision (:artifact result)))
            "baseline: failing internal gate flips LLM :approved → :rejected")
        (is (some? complete-entry)
            ":reviewer/review-complete must fire")
        (is (= [:always-fails] (get-in complete-entry [:data :failing-gate-ids]))
            ":failing-gate-ids on the complete log must name the gate")
        (is (true? (get-in complete-entry [:data :gate-overrode-llm?]))
            ":gate-overrode-llm? must be true when LLM and final differ")
        (is (some? override-entry)
            ":reviewer/gate-overrode-llm warn must fire on disagreement")
        (is (= :approved  (get-in override-entry [:data :llm-decision])))
        (is (= :rejected  (get-in override-entry [:data :final-decision])))
        (is (= [:always-fails] (get-in override-entry [:data :failing-gate-ids])))))))

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

(deftest test-advisory-gate-failure-does-not-block-approval
  ;; Regression: a failing :lint gate (errors carry no :severity, so the old
  ;; :blocking default classified them blocking) flipped a verify-passed,
  ;; LLM-:approved build to :rejected — capping every dogfood spec at the
  ;; :review-approved gate (2026-05-24, workflow adhoc-807481487).
  (testing "a :lint gate failure (no severity) is not a blocking issue"
    (let [failed [(failed-gate-feedback :lint [{:code :lint-error
                                                :message "trailing whitespace"}])]]
      (is (empty? (reviewer/extract-blocking-issues failed)))
      (is (= :conditionally-approved
             (:decision (reviewer/make-review-decision failed {})))
          "non-blocking failure downgrades to :conditionally-approved, not :rejected")))
  (testing "all non-advisory gates block by default (fail-safe), incl. :unknown"
    (doseq [gate-type [:syntax :policy :test :unknown]]
      (let [failed [(failed-gate-feedback gate-type [{:code :err :message "boom"}])]]
        (is (seq (reviewer/extract-blocking-issues failed))
            (str gate-type " failure must remain blocking"))
        (is (= :rejected (:decision (reviewer/make-review-decision failed {})))))))
  (testing "a gate-execution exception (create-exception-feedback) fails safe (blocking)"
    ;; create-exception-feedback yields {:gate-type :unknown :errors [{:type
    ;; :gate-exception ...}]} with no :severity — a crashed gate must block,
    ;; never approve open.
    (let [crashed (reviewer/create-exception-feedback
                   {} 0 (RuntimeException. "gate blew up") 1)]
      (is (false? (:passed? crashed)))
      (is (seq (reviewer/extract-blocking-issues [crashed]))
          "a crashed gate's error must be blocking")
      (is (= :rejected (:decision (reviewer/make-review-decision [crashed] {}))))))
  (testing "explicit :severity overrides gate-type classification"
    (is (seq (reviewer/extract-blocking-issues
              [(failed-gate-feedback :lint [{:message "x" :severity :blocking}])]))
        "an explicit :blocking severity blocks even on an advisory gate")
    (is (empty? (reviewer/extract-blocking-issues
                 [(failed-gate-feedback :policy [{:message "y" :severity :warning}])]))
        "an explicit :warning severity does not block even on a safety-net gate"))
  (testing "LLM :approved survives an advisory gate failure as :conditionally-approved (gate passes)"
    (let [gate-decision (:decision (reviewer/make-review-decision
                                    [(failed-gate-feedback :lint
                                                           [{:message "style"}])] {}))]
      (is (= :conditionally-approved
             (reviewer/merge-gate-overrides :approved gate-decision {}))))))

(deftest test-gate-result-feedback-resolves-real-gate-ids
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
          fb (#'reviewer/gate-result->feedback gate result)]
      (is (= :my-custom (:gate-id fb)))
      (is (= :policy (:gate-type fb)))
      (is (true? (:passed? fb)))))

  (testing "CustomGate result with explicit :gate/id wins over the gate's :id"
    (let [gate (loop/custom-gate :gate-side :policy (fn [_ _] {}))
          result {:gate/id :result-side :gate/type :other :gate/passed? false :gate/errors []}
          fb (#'reviewer/gate-result->feedback gate result)]
      (is (= :result-side (:gate-id fb))
          "result's :gate/id always wins — pass-result/fail-result are authoritative")
      (is (= :other (:gate-type fb)))))

  (testing "CustomGate result without :gate/id falls back to the record's :id"
    (let [gate (loop/custom-gate :on-the-record :policy (fn [_ _] {}))
          result {:gate/passed? false :gate/errors [{:message "boom"}]}
          fb (#'reviewer/gate-result->feedback gate result)]
      (is (= :on-the-record (:gate-id fb))
          ":id from the defrecord must surface when the result is bare")
      (is (= :policy (:gate-type fb))
          ":type-kw from the CustomGate record must surface when the result is bare")))

  (testing "SyntaxGate result resolves to a real id even though the record has no :type-kw field"
    (let [gate (loop/syntax-gate)
          result {:gate/id :syntax :gate/type :syntax :gate/passed? true}
          fb (#'reviewer/gate-result->feedback gate result)]
      (is (= :syntax (:gate-id fb))
          "syntax gate must NOT show as :unknown — it was the canonical regression")
      (is (= :syntax (:gate-type fb)))))

  (testing "missing-id path falls back to :unknown — keeps the existing safety net"
    (let [gate {:not-a-record true}    ; non-record map with no id at all
          result {:gate/passed? false :gate/errors []}
          fb (#'reviewer/gate-result->feedback gate result)]
      (is (= :unknown (:gate-id fb)))
      (is (= :unknown (:gate-type fb))))))

(deftest test-reviewer-no-override-warn-when-gates-and-llm-agree
  (testing "no :reviewer/gate-overrode-llm warn when LLM and gates agree on :approved"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response
                               "```clojure
{:review/decision :approved
 :review/issues []
 :review/summary \"LGTM\"}
```"))
                  llm/success? :success?
                  llm/get-content :content]
      (let [[logger entries] (log/collecting-logger {:min-level :trace})
            reviewer (reviewer/create-reviewer
                       {:llm-backend ::mock-backend
                        :gates []
                        :logger logger})
            _result (core/invoke reviewer {} sample-artifact)
            complete-entry (some #(when (= :reviewer/review-complete (:log/event %)) %)
                                 @entries)
            override-entry (some #(when (= :reviewer/gate-overrode-llm (:log/event %)) %)
                                 @entries)]
        (is (false? (get-in complete-entry [:data :gate-overrode-llm?])))
        (is (empty? (get-in complete-entry [:data :failing-gate-ids])))
        (is (nil? override-entry)
            "no warn when LLM and gates agree — keeps the signal high-value")))))

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
;;
;; Pure-fingerprint cases (review-fingerprint, stagnated?, gate-only-mode,
;; whitespace-normalization, etc.) moved to the
;; :detector/repair-loop port at
;; components/progress-detector/test/ai/miniforge/progress_detector/detectors/repair_loop_test.clj
;; per Stage 2 spec. The agent.reviewer namespace no longer hosts the
;; fingerprint logic, so the tests live with the detector.

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

(deftest reviewer-system-prompt-includes-behavior-addendum-test
  ;; Pins the wiring added so the reviewer agent actually sees the
  ;; standards rules that `phase/load-and-filter-behaviors` produces
  ;; for the :review phase. Without this, every rule violation we've
  ;; added (localization 050, named-constants 006, no-dead-code 008,
  ;; result-handling 003, …) stays invisible to the reviewer and
  ;; slips past review the same way the 2026-05-20 #940 / #941
  ;; localization gaps did.
  (testing ":task/behavior-addendum on the input is appended to the LLM :system opt"
    (let [captured (atom nil)
          parseable-review (str "```clojure\n"
                                "{:review/decision :approved\n"
                                " :review/summary \"ok\"}\n"
                                "```")
          addendum "\n\n## Policy Rules — Required Behaviors\n\n1. Test rule body."]
      (with-redefs [model/resolve-llm-client-for-role
                    (fn [_role provided] provided)
                    llm/chat (fn [_client _prompt opts]
                               (reset! captured opts)
                               {:success? true
                                :content parseable-review
                                :tokens 1})
                    llm/success? :success?
                    llm/get-content :content]
        (let [reviewer (reviewer/create-reviewer
                        {:llm-backend ::mock-backend
                         :gates       []})
              input    (assoc sample-artifact
                              :task/behavior-addendum addendum)]
          (core/invoke reviewer {} input)
          (is (some? @captured) "LLM client should have been called")
          (let [system-prompt (:system @captured)]
            (is (string? system-prompt))
            (is (clojure.string/includes? system-prompt "Policy Rules")
                "appended addendum must surface in the LLM :system opt — the whole point of the wiring")
            (is (clojure.string/includes? system-prompt "Test rule body.")
                "rule body text must reach the LLM verbatim")))))))

(deftest reviewer-system-prompt-empty-when-no-addendum-test
  (testing "absent :task/behavior-addendum is treated as empty — no nil concat"
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
                    llm/success? :success?
                    llm/get-content :content]
        (let [reviewer (reviewer/create-reviewer
                        {:llm-backend ::mock-backend
                         :gates       []})]
          (core/invoke reviewer {} sample-artifact)
          (is (some? @captured))
          (let [system-prompt (:system @captured)]
            (is (string? system-prompt)
                ":system must always be a string (default \"\" when no addendum)")
            ;; The base prompt is unchanged when no addendum is appended;
            ;; assertion is just that we didn't NPE or get nil.
            (is (pos? (count system-prompt)))))))))

;;----------------------------------------------------------------------------- Enumeration-retry validator

(def ^:private enumeration-retry? #'reviewer/enumeration-retry?)

(deftest enumeration-retry-fires-on-rejection-without-blockers
  (testing "a :rejected (or :changes-requested) decision with NO :blocking
            findings inline AND no gate blockers is malformed — the validator
            triggers a re-enumeration"
    (is (true? (boolean (enumeration-retry? :rejected           [] []))))
    (is (true? (boolean (enumeration-retry? :changes-requested  [] []))))
    (is (true? (boolean (enumeration-retry? :rejected
                                            [{:severity :warning :description "nit"}]
                                            []))))))

(deftest enumeration-retry-no-fire-when-blockers-enumerated
  (testing "a rejection with an inline :blocking finding is well-formed"
    (is (false? (boolean (enumeration-retry?
                          :rejected
                          [{:severity :blocking :description "real issue"}]
                          []))))))

(deftest enumeration-retry-no-fire-when-gate-blocks
  (testing "a rejection backed by a deterministic gate blocker is well-formed
            (the implementer has something concrete to fix)"
    (is (false? (boolean (enumeration-retry?
                          :rejected [] ["gate-blocking-issue"]))))))

(deftest enumeration-retry-no-fire-on-non-rejection
  (testing ":approved / :conditionally-approved never trigger the validator"
    (is (false? (boolean (enumeration-retry? :approved [] []))))
    (is (false? (boolean (enumeration-retry? :conditionally-approved [] []))))))

;;----------------------------------------------------------------------------- well-formed-recovery? + recover-review-enumeration

(def ^:private well-formed-recovery? #'reviewer/well-formed-recovery?)
(def ^:private recover-review-enumeration #'reviewer/recover-review-enumeration)

(deftest well-formed-recovery-accepts-approval-correction
  (testing ":approved / :conditionally-approved from the retry are well-formed
            — the retry template explicitly permits self-correction, and
            DISCARDING those would leave the original malformed rejection
            in place (the exact churn the validator exists to eliminate)"
    (is (true? (well-formed-recovery? {:review/decision :approved
                                       :review/issues []})))
    (is (true? (well-formed-recovery? {:review/decision :conditionally-approved
                                       :review/issues [{:severity :nit
                                                        :description "trivial"}]})))))

(deftest well-formed-recovery-accepts-enumerated-rejection
  (is (true? (well-formed-recovery?
              {:review/decision :rejected
               :review/issues   [{:severity :blocking :description "real issue"}]}))))

(deftest well-formed-recovery-rejects-rejection-without-blockers
  (is (false? (well-formed-recovery?
               {:review/decision :rejected :review/issues []}))))

(deftest recover-review-enumeration-returns-approval-correction-test
  (testing "when the retry corrects to :approved, recovery returns it
            (regression guard: previously it required :blocking and
            silently dropped approvals)"
    (let [calls (atom 0)
          stub  "```clojure\n{:review/decision :approved
                              :review/summary \"clean on re-review\"
                              :review/issues []}\n```"]
      (with-redefs [llm/chat (fn [_client _prompt _opts]
                               (swap! calls inc)
                               {:success true :content stub})]
        (let [recovered (recover-review-enumeration
                         :stub-client {} nil "orig user prompt" "prior malformed content")]
          (is (= 1 @calls) "exactly one retry LLM call")
          (is (= :approved (:review/decision recovered))))))))

(deftest recover-review-enumeration-returns-enumerated-rejection-test
  (testing "when the retry enumerates :blocking findings, recovery returns it"
    (let [stub "```clojure\n{:review/decision :rejected
                             :review/issues [{:severity :blocking :description \"x\"}]}\n```"]
      (with-redefs [llm/chat (fn [_client _prompt _opts]
                               {:success true :content stub})]
        (let [recovered (recover-review-enumeration
                         :stub-client {} nil "orig" "prior")]
          (is (= :rejected (:review/decision recovered)))
          (is (= 1 (count (:review/issues recovered)))))))))

(deftest recover-review-enumeration-returns-nil-on-still-malformed-test
  (testing "when the retry ALSO returns a rejection with no blockers,
            recovery returns nil (the raw rejection stands as-is, logged)"
    (let [stub "```clojure\n{:review/decision :rejected :review/issues []}\n```"]
      (with-redefs [llm/chat (fn [_client _prompt _opts]
                               {:success true :content stub})]
        (is (nil? (recover-review-enumeration
                   :stub-client {} nil "orig" "prior")))))))
