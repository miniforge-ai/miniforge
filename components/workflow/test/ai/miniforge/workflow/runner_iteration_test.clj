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

;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.workflow.runner-iteration-test
  "Tests for iteration helpers extracted from runner.clj.
   Includes slingshot try+/throw+ bb-compatibility tests."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.monitoring :as monitoring]
   [ai.miniforge.workflow.phase-test-support :as phase-test-support]
   [ai.miniforge.workflow.runner :as runner]
   [slingshot.slingshot :refer [try+ throw+]]))

(use-fixtures :each phase-test-support/with-workflow-phase-test-support)

(def ^:private runner-test-done
  phase-test-support/runner-test-done)

;; Access private fns
(def ^:private rate-limited? #'runner/rate-limited?)
(defn- backoff-ms [n] (#'runner/backoff-ms n))
(def ^:private make-execution-error #'runner/make-execution-error)

(defn- running-control-state
  []
  (atom {:paused false :stopped false :adjustments {}}))

(defn- halted-supervision-result
  []
  {:status :halt
   :halt-reason "workflow stalled"
   :halting-agent :progress-monitor
   :checks [{:status :halt
             :data {:elapsed-ms 120000}}]})

;; ============================================================================
;; rate-limited?
;; ============================================================================

(deftest rate-limited-detects-429-test
  (testing "detects HTTP 429 in error message"
    (is (true? (rate-limited? "HTTP 429 Too Many Requests")))))

(deftest rate-limited-detects-rate-limit-test
  (testing "detects 'rate limit' phrase"
    (is (true? (rate-limited? "You've hit your rate limit")))))

(deftest rate-limited-detects-quota-exceeded-test
  (testing "detects quota exceeded"
    (is (true? (rate-limited? "API quota exceeded for this model")))))

(deftest rate-limited-false-for-normal-errors-test
  (testing "returns false for non-rate-limit errors"
    (is (false? (rate-limited? "Syntax error on line 10")))
    (is (false? (rate-limited? "Connection refused")))
    (is (false? (rate-limited? "")))))

(deftest rate-limited-handles-nil-test
  (testing "handles nil input"
    (is (false? (rate-limited? nil)))))

(deftest rate-limited-recognises-shapes-the-resilience-layer-knows-test
  (testing "Runner's rate-limited? now delegates to dag-resilience/rate-limit-in-text?,
            so the workflow-phase path catches every shape the resilience layer
            knows — not just the narrow set the prior inline regex covered. The
            patterns themselves live in error-patterns/external.edn and can be
            extended without touching runner.clj. Without this unification a
            vendor-prose throttle signal (e.g. 'resets 2pm') in mid-implement
            would fall through to plain backoff instead of routing through
            apply-rate-limit-failure (which writes a typed terminal so the
            workflow can be resumed cleanly via `mf resume`)."
    ;; Reset-time phrasing without the word "rate" — covered by
    ;; :provider-rate-limit via "resets \\d", NOT by the prior inline
    ;; regex (`rate.?limit|429|hit your limit|quota.?exceeded`). This is
    ;; the marker assertion that proves the delegation went through.
    (is (true? (rate-limited? "Claude CLI exhausted: resets 2pm (America/Los_Angeles)")))
    (is (true? (rate-limited? "exhausted — resets in 30 minutes")))
    ;; Still rejects non-rate-limit errors
    (is (false? (rate-limited? "Syntax error in foo.clj")))
    (is (false? (rate-limited? "Connection refused")))))

;; ============================================================================
;; backoff-ms
;; ============================================================================

(deftest backoff-ms-first-retry-test
  (testing "first retry uses base backoff"
    (is (= 1000 (backoff-ms 1)))))

(deftest backoff-ms-doubles-test
  (testing "second retry doubles"
    (is (= 2000 (backoff-ms 2)))))

(deftest backoff-ms-capped-test
  (testing "large retry count is capped at max"
    (is (<= (backoff-ms 100) 30000))))

;; ============================================================================
;; make-execution-error
;; ============================================================================

(deftest make-execution-error-basic-test
  (testing "produces map with :type and :message"
    (let [err (make-execution-error :test-error "something broke")]
      (is (= :test-error (:type err)))
      (is (= "something broke" (:message err))))))

(deftest make-execution-error-with-extra-test
  (testing "merges extra data"
    (let [err (make-execution-error :test-error "broke" {:data {:code 42}})]
      (is (= :test-error (:type err)))
      (is (= 42 (get-in err [:data :code]))))))

;; ============================================================================
;; slingshot try+/throw+ — bb compatibility tests
;; ============================================================================

(deftest slingshot-throw-plus-map-test
  (testing "throw+ with map is caught by key-value selector"
    (let [result (try+
                   (throw+ {:type :rate-limited :status 429 :message "slow down"})
                   (catch [:type :rate-limited] {:keys [status message]}
                     {:caught true :status status :message message}))]
      (is (true? (:caught result)))
      (is (= 429 (:status result)))
      (is (= "slow down" (:message result))))))

(deftest slingshot-catches-ex-info-by-key-test
  (testing "try+ catches existing ex-info throws by key-value selector"
    (let [result (try+
                   (throw (ex-info "gate failed" {:type :gate-failed :gate :lint}))
                   (catch [:type :gate-failed] {:keys [gate]}
                     {:caught true :gate gate}))]
      (is (true? (:caught result)))
      (is (= :lint (:gate result))))))

(deftest slingshot-fallthrough-to-object-test
  (testing "try+ falls through to Object catch for non-matching ex-info throws"
    (let [result (try+
                   (throw (ex-info "other error" {:type :unknown}))
                   (catch [:type :rate-limited] _
                     {:caught :rate-limited})
                   (catch Object obj
                     {:caught :fallback :type (:type obj)}))]
      (is (= :fallback (:caught result)))
      (is (= :unknown (:type result))))))

(deftest slingshot-dashboard-stop-test
  (testing "check-stopped! throw+ is caught by :type selector"
    (let [result (try+
                   (throw+ {:type :dashboard-stop :reason :dashboard-stop :message "stopped"})
                   (catch [:type :dashboard-stop] {:keys [message]}
                     {:caught true :message message}))]
      (is (true? (:caught result)))
      (is (= "stopped" (:message result))))))

;; ============================================================================
;; execute-single-iteration
;; ============================================================================

(deftest execute-single-iteration-halts-on-supervision-test
  (testing "supervision halt transitions the workflow to failed"
    (let [workflow {:workflow/id :test
                    :workflow/version "1.0.0"
                    :workflow/pipeline [{:phase runner-test-done}]}
          context (ctx/create-context workflow {:task "Test"} {})
          result (with-redefs [#_{:clj-kondo/ignore [:unresolved-var]}
                               monitoring/check-workflow-supervision
                               (fn [_runtime _workflow-state]
                                 (halted-supervision-result))]
                   (runner/execute-single-iteration
                    []
                    context
                    {}
                    1
                    (running-control-state)))]
      (is (= :failed (:execution/status result)))
      (is (some #(= :supervision-halt (:type %))
                (:execution/errors result)))
      (is (= :progress-monitor
             (:supervisor (last (:execution/errors result))))))))
