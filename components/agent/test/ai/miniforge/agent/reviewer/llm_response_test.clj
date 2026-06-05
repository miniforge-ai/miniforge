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

(ns ai.miniforge.agent.reviewer.llm-response-test
  "Tests for `ai.miniforge.agent.reviewer.llm-response` — extracted from
   `ai.miniforge.agent.reviewer-test` as part of the PR-F decomposition.

   Covers EDN parsing of reviewer responses, the failure-message ladder,
   backend-timeout detection, and the enumeration-retry validator +
   recovery loop."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.messages :as msg]
   [ai.miniforge.agent.reviewer.llm-response :as llm-response]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Fixtures

(def ^:private approved-review-content
  "```clojure
{:review/decision :approved
 :review/summary \"clean\"
 :review/issues []}
```")

(def ^:private approved-with-nil-line-content
  ;; The 2026-05-16 nil-line incident: a file-level concern with
  ;; explicit :line nil; the pre-fix schema silently rejected it.
  "```clojure
{:review/decision :approved
 :review/summary \"clean except a file-level concern\"
 :review/issues [{:severity :warning
                  :file \"src/example.clj\"
                  :line nil
                  :description \"missing top-of-file docstring\"
                  :suggestion \"add a one-line ns docstring\"}
                 {:severity :nit
                  :file \"src/example.clj\"
                  :line 10
                  :description \"minor\"}]}
```")

(def ^:private approved-with-nil-optional-fields-content
  "```clojure
{:review/decision :approved
 :review/summary \"file-level concern only\"
 :review/issues [{:severity :warning
                  :file nil
                  :line nil
                  :description \"some general note\"
                  :suggestion nil}]}
```")

(def ^:private malformed-review-issue-content
  ;; Issue severity is not an allowed enum value — parse must reject.
  "```clojure
{:review/decision :rejected
 :review/summary \"x\"
 :review/issues [{:severity :critical :description \"bad enum\"}]}
```")

;------------------------------------------------------------------------------ parse-review-response

(deftest parse-review-response-test
  (testing "valid EDN inside a ```clojure code block round-trips"
    (let [parsed (llm-response/parse-review-response approved-review-content)]
      (is (= :approved (:review/decision parsed)))))

  (testing "valid bare EDN (no fence) also parses"
    (let [parsed (llm-response/parse-review-response
                   "{:review/decision :approved :review/summary \"ok\"}")]
      (is (= :approved (:review/decision parsed)))))

  (testing "valid EDN with malformed issue maps is treated as unparseable"
    (is (nil? (llm-response/parse-review-response malformed-review-issue-content))))

  (testing "issue with :line nil parses with :review/decision intact (2026-05-16 regression)"
    (let [parsed (llm-response/parse-review-response approved-with-nil-line-content)]
      (is (some? parsed) "parser must not return nil for a nil-line issue")
      (is (= :approved (:review/decision parsed)))
      (is (= 2 (count (:review/issues parsed))))
      (is (nil? (:line (first (:review/issues parsed))))
          ":line nil must round-trip, not get dropped")))

  (testing "issue with :file nil, :line nil, :suggestion nil parses cleanly"
    (let [parsed (llm-response/parse-review-response approved-with-nil-optional-fields-content)]
      (is (some? parsed))
      (is (= :approved (:review/decision parsed)))))

  (testing "garbage in → nil out (parse failure)"
    (is (nil? (llm-response/parse-review-response "not edn at all")))
    (is (nil? (llm-response/parse-review-response "")))
    (is (nil? (llm-response/parse-review-response "```clojure\n{:not :a :review}\n```")))))

;------------------------------------------------------------------------------ Failure messages

(deftest review-failure-message-test
  (testing "content-present + parse failure → :unparseable catalog text"
    (is (= (msg/t :reviewer.llm-response/unparseable)
           (llm-response/review-failure-message {} "some unparseable content"))))

  (testing "no content + LLM-client error → the error's :message verbatim"
    (with-redefs [llm/get-error (fn [_] {:message "rate limited at upstream"})]
      (is (= "rate limited at upstream"
             (llm-response/review-failure-message {} "")))))

  (testing "neither content nor LLM error → generic pre-parse catalog text"
    (with-redefs [llm/get-error (fn [_] nil)]
      (is (= (msg/t :reviewer.llm-response/invocation-failed-pre-parse)
             (llm-response/review-failure-message {} ""))))))

(deftest backend-failure-message-test
  (testing "LLM-client error message wins"
    (with-redefs [llm/get-error (fn [_] {:message "5xx upstream"})]
      (is (= "5xx upstream"
             (llm-response/backend-failure-message {} {:review/blocking-issues ["enumerated"]})))))

  (testing "without an LLM error, the first inline :review/blocking-issues wins"
    (with-redefs [llm/get-error (fn [_] nil)]
      (is (= "enumerated"
             (llm-response/backend-failure-message {} {:review/blocking-issues ["enumerated"]})))))

  (testing "without either, falls back to the generic post-parse catalog text"
    (with-redefs [llm/get-error (fn [_] nil)]
      (is (= (msg/t :reviewer.llm-response/invocation-failed-post-parse)
             (llm-response/backend-failure-message {} {}))))))

;------------------------------------------------------------------------------ Backend-timeout detection

(deftest backend-timeout-issue?-test
  (testing "true for known timeout / stagnation patterns (case-insensitive)"
    (is (llm-response/backend-timeout-issue? "Stagnation timeout: no progress for 120119ms"))
    (is (llm-response/backend-timeout-issue? "Adaptive timeout fired"))
    (is (llm-response/backend-timeout-issue? "STREAM-IDLE limit reached"))
    (is (llm-response/backend-timeout-issue? "request timed out"))
    (is (llm-response/backend-timeout-issue? "exceeded 600000ms TIMEOUT")))

  (testing "false for ordinary review findings"
    (is (not (llm-response/backend-timeout-issue? "missing docstring on public defn")))
    (is (not (llm-response/backend-timeout-issue? nil)))
    (is (not (llm-response/backend-timeout-issue? "")))))

(deftest timeout-only-review?-test
  (let [timeout-llm-review {:review/decision :rejected
                            :review/blocking-issues ["Stagnation timeout fired"]
                            :review/recommendations []
                            :review/issues []}
        approved-gate-result {:decision :approved
                              :blocking-issues []
                              :warnings []}]
    (testing "rejected verdict + approved gates + only-timeout blockers → timeout-only"
      (is (llm-response/timeout-only-review? timeout-llm-review approved-gate-result)))

    (testing "non-rejection decision → not timeout-only"
      (is (not (llm-response/timeout-only-review?
                 (assoc timeout-llm-review :review/decision :approved)
                 approved-gate-result))))

    (testing "deterministic gate already rejecting → not timeout-only"
      (is (not (llm-response/timeout-only-review?
                 timeout-llm-review
                 {:decision :rejected :blocking-issues ["real"]}))))

    (testing "non-timeout blockers present → not timeout-only"
      (is (not (llm-response/timeout-only-review?
                 {:review/decision :rejected
                  :review/blocking-issues ["real code issue"]
                  :review/recommendations []
                  :review/issues []}
                 approved-gate-result))))

    (testing "presence of :review/issues → not timeout-only"
      (is (not (llm-response/timeout-only-review?
                 (assoc timeout-llm-review
                        :review/issues [{:severity :blocking :description "x"}])
                 approved-gate-result))))

    (testing "presence of :review/recommendations → not timeout-only"
      (is (not (llm-response/timeout-only-review?
                 (assoc timeout-llm-review :review/recommendations ["something"])
                 approved-gate-result))))))

;------------------------------------------------------------------------------ Enumeration-retry validator

(deftest enumeration-retry?-test
  (testing "rejection without blockers AND no gate blockers → fires"
    (is (true? (boolean (llm-response/enumeration-retry? :rejected           [] []))))
    (is (true? (boolean (llm-response/enumeration-retry? :changes-requested  [] []))))
    (is (true? (boolean (llm-response/enumeration-retry?
                          :rejected
                          [{:severity :warning :description "nit"}]
                          [])))))

  (testing "rejection with inline :blocking finding → does NOT fire"
    (is (false? (boolean (llm-response/enumeration-retry?
                           :rejected
                           [{:severity :blocking :description "real issue"}]
                           []))))
    "the implementer has a concrete blocker to fix")

  (testing "rejection backed by deterministic gate blocker → does NOT fire"
    (is (false? (boolean (llm-response/enumeration-retry?
                           :rejected [] ["gate-blocking-issue"])))))

  (testing ":approved / :conditionally-approved never fire the validator"
    (is (false? (boolean (llm-response/enumeration-retry? :approved [] []))))
    (is (false? (boolean (llm-response/enumeration-retry? :conditionally-approved [] []))))))

;------------------------------------------------------------------------------ well-formed-recovery?

(deftest well-formed-recovery?-test
  (testing ":approved / :conditionally-approved from the retry are well-formed
            (the retry template explicitly permits self-correction)"
    (is (true? (llm-response/well-formed-recovery?
                 {:review/decision :approved
                  :review/issues []})))
    (is (true? (llm-response/well-formed-recovery?
                 {:review/decision :conditionally-approved
                  :review/issues [{:severity :nit :description "trivial"}]}))))

  (testing "rejection with enumerated :blocking findings is well-formed"
    (is (true? (llm-response/well-formed-recovery?
                 {:review/decision :rejected
                  :review/issues   [{:severity :blocking :description "real issue"}]}))))

  (testing "rejection without blockers is NOT well-formed"
    (is (false? (llm-response/well-formed-recovery?
                  {:review/decision :rejected :review/issues []}))))

  (testing "unknown :review/decision (typo / nil / unrecognized) is NOT well-formed"
    ;; parse-review-response doesn't validate the enum, so the validator
    ;; must — otherwise a typo or nil slips past as 'non-rejection' and
    ;; gets collapsed downstream to :changes-requested, reintroducing
    ;; the malformed rejection.
    (is (false? (llm-response/well-formed-recovery? {:review/decision :approve  ; typo
                                                     :review/issues []})))
    (is (false? (llm-response/well-formed-recovery? {:review/decision nil
                                                     :review/issues []})))
    (is (false? (llm-response/well-formed-recovery? {:review/decision :looks-good
                                                     :review/issues []})))))

;------------------------------------------------------------------------------ recover-review-enumeration

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
        (let [recovered (llm-response/recover-review-enumeration
                         :stub-client {} nil "orig user prompt" "prior malformed content")]
          (is (= 1 @calls) "exactly one retry LLM call")
          (is (= :approved (:review/decision recovered))))))))

(deftest recover-review-enumeration-returns-enumerated-rejection-test
  (testing "when the retry enumerates :blocking findings, recovery returns it"
    (let [stub "```clojure\n{:review/decision :rejected
                             :review/issues [{:severity :blocking :description \"x\"}]}\n```"]
      (with-redefs [llm/chat (fn [_client _prompt _opts]
                               {:success true :content stub})]
        (let [recovered (llm-response/recover-review-enumeration
                         :stub-client {} nil "orig" "prior")]
          (is (= :rejected (:review/decision recovered)))
          (is (= 1 (count (:review/issues recovered)))))))))

(deftest recover-review-enumeration-returns-nil-on-still-malformed-test
  (testing "when the retry ALSO returns a rejection with no blockers,
            recovery returns nil (the raw rejection stands as-is, logged)"
    (let [stub "```clojure\n{:review/decision :rejected :review/issues []}\n```"]
      (with-redefs [llm/chat (fn [_client _prompt _opts]
                               {:success true :content stub})]
        (is (nil? (llm-response/recover-review-enumeration
                    :stub-client {} nil "orig" "prior")))))))
