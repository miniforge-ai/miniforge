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
   [clojure.string :as str]
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.agent.reviewer :as reviewer]
   [ai.miniforge.agent.reviewer.artifact :as r-artifact]
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
  ;; `:task/scope` carries the broad fixture scope so the reviewer's
  ;; required-scope contract (PR #1039) doesn't trip tests that aren't
  ;; about scope per se. Scope-specific tests below override or remove
  ;; this when they need to exercise scope behavior explicitly.
  {:artifact/id (random-uuid)
   :artifact/type :code
   :artifact/content {:code/files [{:path "src/example.clj"
                                    :content "(ns example)\n(defn hello [] \"world\")"
                                    :action :create}]}
   :task/scope ["src" "components" "bases"]})

(defn- review-gate-feedback
  ([] (review-gate-feedback :unknown 0))
  ([gate-id duration-ms]
   {:gate-id gate-id
    :gate-type :unknown
    :passed? true
    :errors []
    :warnings []
    :duration-ms duration-ms}))

(deftest split-review-failure-normalizes-response-map-test
  (testing "aggregate tokens are added to a valid failed response map"
    (let [combined (#'reviewer/combine-normalized-review-results
                    [{:parsed-content nil
                      :content "failed"
                      :tokens 3
                      :response {:success false :error :timeout}}])]
      (is (= {:success false :error :timeout :tokens 3}
             (:response combined)))))
  (testing "absent and malformed failed responses become maps"
    (doseq [response [nil false :not-a-map 42 []]
            :let [combined (#'reviewer/combine-normalized-review-results
                            [{:parsed-content nil
                              :content "failed"
                              :tokens 3
                              :response response}])]]
      (is (= {:tokens 3} (:response combined))
          (str "normalized " (pr-str response))))))

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

(defn- stream-idle-llm-error
  "Build the LLM-error shape `streaming-error-response` produces when the
   stream-idle adaptive timeout fires — the same shape `llm/get-error`
   surfaces. The 2026-06-05 dogfood (`adhoc-944448986`) hit exactly
   this path on the reviewer LLM call."
  [elapsed-ms]
  (let [message (str "Adaptive timeout: No stream output for " elapsed-ms
                     "ms (type: stream-idle, elapsed: " elapsed-ms "ms)")]
    {:type backend-timeout-type
     :message message
     :timeout {:type :stream-idle
               :message (str "No stream output for " elapsed-ms "ms")
               :elapsed-ms elapsed-ms
               :stats {:lines-read 0}}}))

(defn- stagnation-llm-error
  "Build the LLM-error shape `streaming-error-response` produces when the
   STAGNATION adaptive timeout fires (provider streamed, but made no forward
   progress for the configured window). The 2026-06-14 dogfood (`rn-03`) hit
   exactly this path on review cycle #3 — and the OLD stream-idle-only check
   missed it, synthesizing a false :rejected."
  [elapsed-ms]
  (let [message (str "Adaptive timeout: Stagnation timeout: no progress for "
                     elapsed-ms "ms (type: stagnation, elapsed: " elapsed-ms "ms)")]
    {:type backend-timeout-type
     :message message
     :timeout {:type :stagnation
               :message (str "no progress for " elapsed-ms "ms")
               :elapsed-ms elapsed-ms}}))

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

        (is (r-artifact/approved? review)
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

        (is (not (r-artifact/approved? review))
            "approved? should return false")

        (is (r-artifact/conditionally-approved? review)
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

      (is (r-artifact/rejected? review)
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

;; parse-review-response tests (incl. malformed-issue, nil-line, all-nil-
;; optional) moved to reviewer/llm_response_test.clj (PR-F).

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

(deftest test-reviewer-boundary-stream-idle-is-agent-error
  ;; The 2026-06-05 dogfood (`adhoc-944448986`) shape: the reviewer LLM
  ;; stream-idle'd at 360s and returned NO parsed review (parse-failed),
  ;; only the LLM-error's `:timeout {:type :stream-idle}`. The OLD
  ;; `timeout-only-review?` predicate read from the parsed review map
  ;; and so could not fire — the framework then promoted the timeout
  ;; text into `:review/blocking-issues` via the parse-failed branch
  ;; and synthesized a false `:rejected`, which the `:review-approved`
  ;; gate (correctly given the verdict it received) failed.
  ;;
  ;; With the boundary-level `result-boundary/stream-idle-error?`
  ;; check wired in, the reviewer must take the `:reviewer/backend-
  ;; timeout` exit — the same path the parseable variants already
  ;; take — instead of producing a false rejection.
  (testing "stream-idle on the LLM-call boundary (no parsed review) → :reviewer/backend-timeout"
    (let [stream-idle-elapsed-ms 360087
          err (stream-idle-llm-error stream-idle-elapsed-ms)]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                    llm/chat (fn [_client _prompt _opts]
                               (mock-llm-response ""
                                                  :success? false
                                                  :tokens timeout-review-token-count
                                                  :error err))
                    llm/success? :success?
                    llm/get-content :content
                    llm/get-error :error]
        (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                  :gates []})
              result (core/invoke reviewer {} sample-artifact)]
          (is (= :error (:status result))
              "Stream-idle must surface as an agent-level error, not a synthetic :rejected review")
          (is (= :reviewer/backend-timeout
                 (get-in result [:error :data :code]))
              "Error code must be :reviewer/backend-timeout — distinguishes infra timeout from real defect")
          (is (= (:message err)
                 (get-in result [:error :message]))
              "Error message preserves the adaptive-timeout text verbatim for operator post-mortem")
          (is (= timeout-review-token-count
                 (get-in result [:metrics :tokens])))
          (is (= []
                 (get-in result [:error :data :blocking-issues]))
              "blocking-issues defaults to an empty vector — never nil — when the
               boundary-stream-idle path fires with no parsed review. Downstream
               consumers that pattern-match on sequential data (count, seq, into)
               must not see a surprise nil from the new path.")
          (is (vector? (get-in result [:error :data :blocking-issues]))
              "vector shape preserved end-to-end — pinned because PR #1058 review
               flagged the original implementation returning nil here."))))))

(deftest test-reviewer-boundary-stagnation-is-agent-error
  ;; The 2026-06-14 dogfood (`rn-03`) shape: on review cycle #3 the reviewer
  ;; LLM streamed but stagnated (no forward progress for 318398ms) and returned
  ;; NO parsed review. The OLD boundary check tested only `stream-idle-error?`,
  ;; so this fell through to the parse-failed branch — the framework promoted
  ;; the timeout text into `:review/blocking-issues` and synthesized a false
  ;; `:rejected` (decision routed back to IMPLEMENT, wasting a redirect cycle
  ;; "fixing" a non-rejection). The widened `backend-timeout-error?` covers
  ;; `:stagnation` (and `:hard-limit`) too, so this must take the same
  ;; `:reviewer/backend-timeout` exit as the stream-idle variant.
  (testing "stagnation on the LLM-call boundary (no parsed review) → :reviewer/backend-timeout"
    (let [stagnation-elapsed-ms 318398
          err (stagnation-llm-error stagnation-elapsed-ms)]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                    llm/chat (fn [_client _prompt _opts]
                               (mock-llm-response ""
                                                  :success? false
                                                  :tokens timeout-review-token-count
                                                  :error err))
                    llm/success? :success?
                    llm/get-content :content
                    llm/get-error :error]
        (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                  :gates []})
              result (core/invoke reviewer {} sample-artifact)]
          (is (= :error (:status result))
              "Stagnation must surface as an agent-level error, not a synthetic :rejected review")
          (is (= :reviewer/backend-timeout
                 (get-in result [:error :data :code]))
              "Error code must be :reviewer/backend-timeout — distinguishes infra timeout from real defect")
          (is (= (:message err)
                 (get-in result [:error :message]))
              "Error message preserves the adaptive-timeout text verbatim for operator post-mortem")
          (is (= []
                 (get-in result [:error :data :blocking-issues]))
              "blocking-issues defaults to an empty vector — never a synthetic rejection"))))))

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

;; Gate-decision and gate-result->feedback tests moved to
;; reviewer/gates_test.clj (PR-D).

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

;; Schema validation tests moved to reviewer/artifact_test.clj (PR-G).

;; review-summary, decision predicates, and get-* accessor tests moved to
;; reviewer/artifact_test.clj (PR-G).
;; test-validate-review-artifact also moved (it covers artifact/validate-).

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

;; Enumeration-retry validator, well-formed-recovery?, recover-review-
;; enumeration tests moved to reviewer/llm_response_test.clj (PR-F).
;; normalize-llm-decision tests moved to reviewer/issues_test.clj (PR-C).

;------------------------------------------------------------------------------ Scope-boundary tests
;; The reviewer prompt and helper resolve a per-task scope so adjacent-file
;; findings can't drive the verdict on tightly-scoped refactors. The 2026-06-04
;; eliminate-requiring-resolve dogfood failed 20/20 tasks because the reviewer
;; held every diff to the FULL standards bar across all changed files.
;;
;; Pure-helper coverage moved to `ai.miniforge.agent.reviewer.scope-test`
;; (PR #1039 decomposition). Kept here: the reviewer-side wiring tests —
;; prompt rendering and the end-to-end `core/invoke` integration.

;; build-review-prompt + format-artifact-for-review tests moved to
;; reviewer/prompts_test.clj (PR-E).
;; sanitize-review-issues tests moved to reviewer/issues_test.clj (PR-C).

(def ^:private scope-test-review-content
  ;; Two blocking findings: one in scope (components/foo/src/a.clj), one
  ;; adjacent-file noise (components/baz/src/b.clj). With the post-LLM
  ;; filter active, only the in-scope finding should drive the verdict;
  ;; the adjacent one should land in :review/out-of-scope-observations.
  (str "```clojure\n"
       "{:review/decision :changes-requested\n"
       " :review/issues [{:severity :blocking\n"
       "                  :file \"components/foo/src/a.clj\"\n"
       "                  :description \"in-scope blocker\"}\n"
       "                 {:severity :blocking\n"
       "                  :file \"components/baz/src/b.clj\"\n"
       "                  :description \"adjacent-file blocker\"}]\n"
       " :review/summary \"mixed\"}\n"
       "```"))

(deftest test-reviewer-filters-out-of-scope-issues-before-verdict
  (testing "LLM findings outside the task scope are moved to observations and do not block"
    (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                  llm/chat (fn [_client _prompt _opts]
                             (mock-llm-response scope-test-review-content))
                  llm/success? :success?
                  llm/get-content :content]
      (let [reviewer (reviewer/create-reviewer {:llm-backend ::mock-backend
                                                :gates []})
            input    (assoc sample-artifact
                            :task/scope ["components/foo/src"])
            result   (core/invoke reviewer {} input)
            review   (:artifact result)
            issues   (vec (:review/issues review))
            out      (vec (:review/out-of-scope-observations review))]
        (is (= 1 (count issues))
            "only the in-scope blocker remains as a verdict-driving issue")
        (is (= "in-scope blocker" (:description (first issues))))
        (is (= 1 (count out))
            "the adjacent-file blocker is preserved as an advisory observation")
        (is (= "adjacent-file blocker" (:description (first out))))))))

;; Tool-disallow-list — the reviewer is forced onto the MCP context cache and
;; blocked from all mutation (Fable #4 context side, reviewer rewire).

(deftest reviewer-disallowed-tools-contents-test
  (testing "names the native read/search tools (forced onto context_*)"
    (let [dt @#'reviewer/reviewer-disallowed-tools]
      (doseq [t ["Read" "Grep" "Glob" "LS" "Agent"]]
        (is (some #{t} dt) (str "expected " t " in disallowed-tools")))))

  (testing "blocks ALL mutation — the reviewer inspects, never writes"
    (let [dt @#'reviewer/reviewer-disallowed-tools]
      (doseq [t ["Write" "Edit" "MultiEdit" "Bash"]]
        (is (some #{t} dt) (str t " must be disallowed for a read-only reviewer")))))

  (testing "is exactly the native tool set — the MCP read tools are allowlisted
            by their prefixed wire names (e.g. mcp__context__context_read) and
            are never in this native disallow-list, so reads route through the
            cache. Pinned so any change is deliberate."
    (is (= #{"Read" "Grep" "Glob" "LS" "Agent" "Bash" "Write" "Edit" "MultiEdit"}
           (set @#'reviewer/reviewer-disallowed-tools)))))

;------------------------------------------------------------------------------ Context-budget shedding (N12 §5)

(def ^:private assemble-review-within-budget #'reviewer/assemble-review-within-budget)

(defn- code-artifact
  "n files, each `chars` bytes of body — the inlined contributor the reviewer
   sheds when the prompt would overflow."
  [n chars]
  {:code/files (vec (for [i (range n)]
                      {:path (str "src/f" i ".clj")
                       :action :modify
                       :content (apply str (repeat chars \x))}))})

(defn- review-input [artifact]
  {:task/title "T" :task/scope ["src"] :task/artifact artifact})

(deftest assemble-review-within-budget-test
  (testing "uncatalogued model (nil window) → never sheds, full bodies inlined"
    (let [r (assemble-review-within-budget (review-input (code-artifact 1 100))
                                           "system" "no-such-model" 0)]
      (is (false? (:shed? r)))
      (is (false? (:over-after-shed? r)))
      (is (str/includes? (:user-prompt r) (apply str (repeat 100 \x))))))

  (testing "a small artifact under the window is not shed"
    (let [r (assemble-review-within-budget (review-input (code-artifact 1 100))
                                           "system" "codellama-34b" 0)]
      (is (false? (:shed? r)))
      (is (str/includes? (:user-prompt r) (apply str (repeat 100 \x))))))

  (testing "an artifact that blows the window sheds bodies to a manifest: the
            path + the context_read hint stay, the inlined body drops, the
            prompt now fits, and the file-count is reported"
    (let [r (assemble-review-within-budget (review-input (code-artifact 1 300000))
                                           "system" "codellama-34b" 0)]
      (is (true? (:shed? r)))
      (is (false? (:over-after-shed? r)))
      (is (= 1 (:file-count r)))
      (is (< (:est-final r) (:est-full r)))
      (is (str/includes? (:user-prompt r) "src/f0.clj"))
      (is (str/includes? (:user-prompt r) "context_read"))
      (is (not (str/includes? (:user-prompt r) (apply str (repeat 5000 \x)))))))

  (testing "the reserve fires the shed earlier — a prompt that fits the full
            window sheds once the reserve drops the effective window below it"
    (let [input      (review-input (code-artifact 1 50000))
          no-reserve (assemble-review-within-budget input "system" "codellama-34b" 0)
          window     (:window no-reserve)
          est        (:est-full no-reserve)
          reserve    (+ (- window est) 1000)
          reserved   (assemble-review-within-budget input "system" "codellama-34b" reserve)]
      (is (false? (:shed? no-reserve)) "fits the full window with no reserve")
      (is (true? (:shed? reserved)) "reserve pushes it past the effective window")
      (is (false? (:reserve-clamped? reserved)))
      (is (= (- window reserve) (:effective-window reserved)))))

  (testing "no inlined file bodies (empty :code/files) + a huge description →
            irreducible overflow, no shed"
    (let [r (assemble-review-within-budget
             {:task/title "T" :task/scope ["src"]
              :task/description (apply str (repeat 300000 \y))
              :task/artifact {:code/files []}}
             "system" "codellama-34b" 0)]
      (is (false? (:shed? r)))
      (is (true? (:over-after-shed? r))))))

(deftest test-reviewer-context-overflow-splits-policy-addendum-across-llm-sessions
  (testing "an over-budget compiled policy addendum is split across multiple
            LLM sessions instead of silently skipping semantic policy review"
    (let [captured-systems (atom [])
          addendum "\n\n## Policy Rules — Required Behaviors\n\n1. RULE-1: check public API boundaries.\n\n2. RULE-2: check prompt templating.\n\n3. RULE-3: check faithful standards application."]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                    ;; Reserve clamps to 100; all three rules overflow
                    ;; together, while the base prompt and any one rule fit.
                    llm/context-window-for-model-id (fn [_] 200)
                    llm/prompt-size-telemetry
                    (fn [system user-prompt]
                      {:estimated-input-tokens
                       (+ 40
                          (* 30 (count (re-seq #"RULE-" system)))
                          (if (str/includes? user-prompt "_File bodies omitted")
                            5
                            80))})
                    llm/chat (fn [_ _ opts]
                               (swap! captured-systems conj (:system opts))
                               (mock-llm-response valid-review-content
                                                  :tokens 2))
                    llm/chat-stream (fn [_ _ _ opts]
                                      (swap! captured-systems conj (:system opts))
                                      (mock-llm-response valid-review-content
                                                         :tokens 2))
                    llm/success? :success?
                    llm/get-content :content]
        (let [reviewer (reviewer/create-reviewer
                        {:llm-backend ::mock-backend
                         :gates [(passing-gate :policy-check)]})
              input (assoc (review-input (code-artifact 1 5000))
                           :task/behavior-addendum addendum)
              result (core/invoke reviewer {} input)]
          (is (= :success (:status result)))
          (is (< 1 (count @captured-systems))
              "policy application must be split into multiple LLM sessions")
          (doseq [rule ["RULE-1" "RULE-2" "RULE-3"]]
            (is (= 1 (count (filter #(str/includes? % rule)
                                    @captured-systems)))
                (str rule " should be applied by exactly one split session")))
          (is (= 1 (get-in result [:metrics :gates-passed]))
              "deterministic gates still run alongside split LLM review")
          (is (= (* 2 (count @captured-systems))
                 (get-in result [:metrics :tokens]))
              "token accounting sums every split LLM session"))))))

(deftest test-reviewer-split-session-summary-fallback
  (testing "split reviews without per-session summaries still produce a useful summary"
    (let [combine #'reviewer/combine-parsed-reviews
          result (combine [{:review/decision :approved
                            :review/issues []}
                           {:review/decision :changes-requested
                            :review/issues []}])]
      (is (= :changes-requested (:review/decision result)))
      (is (= "Split reviewer sessions completed."
             (:review/summary result))))))

(deftest test-reviewer-irreducible-context-overflow-applies-gates-without-llm
  (testing "irreducible context overflow skips the doomed LLM call but still
            runs deterministic policy gates"
    (let [llm-called? (atom false)]
      (with-redefs [model/resolve-llm-client-for-role (fn [_role client] client)
                    llm/context-window-for-model-id (fn [_] 100)
                    llm/chat (fn [_ _ _]
                               (reset! llm-called? true)
                               (mock-llm-response valid-review-content))
                    llm/chat-stream (fn [_ _ _ _]
                                      (reset! llm-called? true)
                                      (mock-llm-response valid-review-content))]
        (let [reviewer (reviewer/create-reviewer
                        {:llm-backend ::mock-backend
                         :gates [(passing-gate :policy-check)]})
              result (core/invoke reviewer {} sample-artifact)]
          (is (false? @llm-called?) "the doomed LLM call is skipped")
          (is (= :error (:status result))
              "returns an infra failure, not a review verdict")
          (is (= :reviewer/context-overflow
                 (get-in result [:error :data :code])))
          (is (= 1 (get-in result [:metrics :gates-passed]))
              "the deterministic policy gate still ran")
          (is (= 0 (get-in result [:metrics :tokens]))))))))
