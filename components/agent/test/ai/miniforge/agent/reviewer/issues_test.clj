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

(ns ai.miniforge.agent.reviewer.issues-test
  "Tests for `ai.miniforge.agent.reviewer.issues` — the canonical issue
   shape, sanitizer, decision normalizer, and decision-enum predicates
   extracted from `ai.miniforge.agent.reviewer` in the PR-C decomposition."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.reviewer.issues :as issues]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Factories

(defn- review-issue
  "Build a canonical-shape `ReviewIssue` map for tests. Defaults to the
   smallest valid issue; overrides via keyword args."
  [& {:keys [severity file description suggestion line]
      :or   {severity    :blocking
             description "test issue"}}]
  (cond-> {:severity severity :description description}
    file       (assoc :file file)
    suggestion (assoc :suggestion suggestion)
    line       (assoc :line line)))

;------------------------------------------------------------------------------ Schemas

(deftest review-issue-schema-tolerates-nil-optional-fields-test
  ;; The 2026-05-16 event-log-tool-visibility incident root-cause was a
  ;; strict {:optional true} read silently rejecting an LLM-`:approved`
  ;; review that emitted explicit `:line nil` for a file-level concern.
  ;; This test pins the `:maybe` wrapping that fix introduced.
  (testing "ReviewIssue accepts explicit nil :line / :file / :suggestion"
    (is (m/validate issues/ReviewIssue (review-issue :file nil :line nil :suggestion nil))))

  (testing "ReviewIssue rejects unknown :severity"
    (is (not (m/validate issues/ReviewIssue (review-issue :severity :critical))))))

;------------------------------------------------------------------------------ sanitize-review-issues

(deftest sanitize-review-issues-test
  (testing "valid canonical issue maps pass through"
    (is (= [(review-issue :description "x")]
           (issues/sanitize-review-issues
             [(review-issue :description "x")]))))

  (testing "malformed entries are dropped, valid ones kept"
    ;; Without the sanitizer, a single bad entry would fail malli
    ;; validation on the whole ReviewArtifact (see ReviewIssue schema's
    ;; docstring on the 2026-05-16 :rejected-on-nil-line incident).
    (let [bad   [{:severity :not-an-enum :description "bad severity"}
                 {:extra-key "extras get rejected too" :severity :nit :description "x"}
                 "string instead of map"
                 nil]
          good  [(review-issue :description "good")
                 (review-issue :severity :warning :description "good 2")]
          mixed (interleave good bad)]
      (is (= good (issues/sanitize-review-issues mixed)))))

  (testing "nil and non-collection inputs return empty vec"
    (is (= [] (issues/sanitize-review-issues nil)))
    (is (= [] (issues/sanitize-review-issues [])))))

;------------------------------------------------------------------------------ valid-review-issues?

(deftest valid-review-issues?-test
  (testing "true when :review/issues is absent"
    (is (issues/valid-review-issues? {:review/decision :approved})))

  (testing "true when :review/issues is a vec of canonical entries"
    (is (issues/valid-review-issues?
          {:review/issues [(review-issue :description "good")]})))

  (testing "false when any entry is malformed"
    ;; The single bad entry triggers a parse-time reject — better than
    ;; letting the whole ReviewArtifact malli check fail on shape later.
    (is (not (issues/valid-review-issues?
               {:review/issues [(review-issue :description "good")
                                {:severity :not-an-enum :description "bad"}]}))))

  (testing "false when :review/issues isn't a vector"
    (is (not (issues/valid-review-issues?
               {:review/issues '({:severity :blocking :description "list not vec"})})))))

;------------------------------------------------------------------------------ Decision predicates

(deftest review-has-blocking?-test
  (testing "true when at least one entry is :blocking"
    (is (issues/review-has-blocking?
          [(review-issue :severity :warning :description "w")
           (review-issue :severity :blocking :description "b")])))

  (testing "false when no entry is :blocking"
    (is (not (issues/review-has-blocking?
               [(review-issue :severity :warning :description "w")
                (review-issue :severity :nit     :description "n")]))))

  (testing "false on empty / nil"
    (is (not (issues/review-has-blocking? [])))
    (is (not (issues/review-has-blocking? nil)))))

(deftest rejection-decisions-membership-test
  ;; `:conditionally-approved` is intentionally NOT a rejection — pin the
  ;; membership so a future "simplification" can't fold it back in.
  (is (contains? issues/rejection-decisions :rejected))
  (is (contains? issues/rejection-decisions :changes-requested))
  (is (not (contains? issues/rejection-decisions :conditionally-approved)))
  (is (not (contains? issues/rejection-decisions :approved))))

(deftest valid-decisions-mirrors-schema-enum-test
  ;; If ReviewArtifact's :review/decision enum gains a value, this set
  ;; must follow — `well-formed-recovery?` keys off it to reject typos.
  (is (= #{:approved :rejected :conditionally-approved :changes-requested}
         issues/valid-decisions)))

(deftest normalize-llm-decision-preserves-conditionally-approved-test
  ;; The ReviewArtifact schema allows `:conditionally-approved`; collapsing
  ;; it to `:changes-requested` (a rejection-class decision) would
  ;; misclassify a legitimate conditional approval as a rejection and
  ;; defeat the enumeration validator + retry self-correction.
  (is (= :conditionally-approved
         (issues/normalize-llm-decision :conditionally-approved)))
  (testing "round-trip for the other valid enums"
    (is (= :approved          (issues/normalize-llm-decision :approved)))
    (is (= :rejected          (issues/normalize-llm-decision :rejected)))
    (is (= :changes-requested (issues/normalize-llm-decision :changes-requested))))
  (testing "unknown decisions fall back to :changes-requested"
    (is (= :changes-requested (issues/normalize-llm-decision :approve)))
    (is (= :changes-requested (issues/normalize-llm-decision nil)))))

;------------------------------------------------------------------------------ Issue-text extraction

(deftest llm-issues->blocking-strings-test
  (let [issues-in [(review-issue :severity :blocking :description "b1")
                   (review-issue :severity :warning  :description "w")
                   (review-issue :severity :blocking :description "b2")
                   (review-issue :severity :nit      :description "n")]]
    (is (= ["b1" "b2"] (issues/llm-issues->blocking-strings issues-in)))))

(deftest llm-issues->warning-strings-test
  (let [issues-in [(review-issue :severity :blocking :description "b")
                   (review-issue :severity :warning  :description "w1")
                   (review-issue :severity :warning  :description "w2")]]
    (is (= ["w1" "w2"] (issues/llm-issues->warning-strings issues-in)))))
