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

(ns ai.miniforge.pr-lifecycle.review-monitor-test
  "Unit tests for review monitoring.

   Tests review status computation, comment tracking, review decision
   parsing, monitor lifecycle, and mocked gh CLI interactions."
  (:require
   [babashka.process]
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.pr-lifecycle.review-monitor :as review]
   [ai.miniforge.dag-executor.interface :as dag]))

;------------------------------------------------------------------------------ Review States

(deftest review-states-test
  (testing "All expected review states are defined"
    (is (= 5 (count review/review-states)))
    (are [state] (contains? review/review-states state)
      :pending :approved :changes-requested :commented :dismissed)))

;------------------------------------------------------------------------------ Review Decision Parsing

(deftest parse-review-decision-approved-test
  (testing "APPROVED parses correctly"
    (is (= :approved (review/parse-review-decision "APPROVED")))))

(deftest parse-review-decision-changes-requested-test
  (testing "CHANGES_REQUESTED parses correctly"
    (is (= :changes-requested (review/parse-review-decision "CHANGES_REQUESTED")))))

(deftest parse-review-decision-review-required-test
  (testing "REVIEW_REQUIRED parses to :pending"
    (is (= :pending (review/parse-review-decision "REVIEW_REQUIRED")))))

(deftest parse-review-decision-empty-test
  (testing "Empty/nil decision parses to :pending"
    (is (= :pending (review/parse-review-decision "")))
    (is (= :pending (review/parse-review-decision nil)))))

(deftest parse-review-decision-case-insensitive-test
  (testing "Decision parsing is case-insensitive"
    (is (= :approved (review/parse-review-decision "approved")))
    (is (= :changes-requested (review/parse-review-decision "changes_requested")))))

(deftest parse-review-decision-unknown-value-test
  (testing "Unrecognized decision defaults to :pending"
    (is (= :pending (review/parse-review-decision "SOMETHING_ELSE")))
    (is (= :pending (review/parse-review-decision "dismissed")))))

;------------------------------------------------------------------------------ Review Status Computation

(deftest compute-review-status-single-approval-test
  (testing "Single approval meets single-approval requirement"
    (let [reviews [{:author "alice" :state "APPROVED"}]
          result (review/compute-review-status reviews 1)]
      (is (= :approved (:status result)))
      (is (= 1 (:approval-count result)))
      (is (contains? (set (:approvers result)) "alice")))))

(deftest compute-review-status-multiple-approvals-test
  (testing "Multiple approvals meet higher requirement"
    (let [reviews [{:author "alice" :state "APPROVED"}
                   {:author "bob" :state "APPROVED"}]
          result (review/compute-review-status reviews 2)]
      (is (= :approved (:status result)))
      (is (= 2 (:approval-count result))))))

(deftest compute-review-status-insufficient-approvals-test
  (testing "Insufficient approvals yield :pending"
    (let [reviews [{:author "alice" :state "APPROVED"}]
          result (review/compute-review-status reviews 2)]
      (is (= :pending (:status result)))
      (is (= 1 (:approval-count result)))
      (is (= 2 (:required-approvals result))))))

(deftest compute-review-status-changes-requested-test
  (testing "Changes requested takes precedence over approvals"
    (let [reviews [{:author "alice" :state "APPROVED"}
                   {:author "bob" :state "CHANGES_REQUESTED"}]
          result (review/compute-review-status reviews 1)]
      (is (= :changes-requested (:status result)))
      (is (= ["bob"] (:changes-requested-by result))))))

(deftest compute-review-status-same-reviewer-override-test
  (testing "Same reviewer's changes-requested cancels their approval"
    (let [reviews [{:author "alice" :state "APPROVED"}
                   {:author "alice" :state "CHANGES_REQUESTED"}]
          result (review/compute-review-status reviews 1)]
      (is (= :changes-requested (:status result)))
      ;; alice's approval is cancelled by their changes-requested
      (is (= 0 (:approval-count result))))))

(deftest compute-review-status-empty-reviews-test
  (testing "No reviews yields :pending"
    (let [result (review/compute-review-status [] 1)]
      (is (= :pending (:status result)))
      (is (= 0 (:approval-count result))))))

;------------------------------------------------------------------------------ Multiple Reviewer Handling

(deftest compute-review-status-three-reviewers-mixed-test
  (testing "Three reviewers with mixed states"
    (let [reviews [{:author "alice" :state "APPROVED"}
                   {:author "bob" :state "CHANGES_REQUESTED"}
                   {:author "charlie" :state "APPROVED"}]
          result (review/compute-review-status reviews 2)]
      ;; changes-requested from bob takes precedence
      (is (= :changes-requested (:status result)))
      (is (= ["bob"] (:changes-requested-by result)))
      ;; alice and charlie approved, bob did not cancel theirs
      (is (= 2 (:approval-count result)))
      (is (contains? (set (:approvers result)) "alice"))
      (is (contains? (set (:approvers result)) "charlie")))))

(deftest compute-review-status-multiple-changes-requested-test
  (testing "Multiple reviewers requesting changes"
    (let [reviews [{:author "alice" :state "CHANGES_REQUESTED"}
                   {:author "bob" :state "CHANGES_REQUESTED"}
                   {:author "charlie" :state "APPROVED"}]
          result (review/compute-review-status reviews 1)]
      (is (= :changes-requested (:status result)))
      (is (= 2 (count (:changes-requested-by result))))
      (is (= #{"alice" "bob"} (set (:changes-requested-by result)))))))

(deftest compute-review-status-all-approved-high-threshold-test
  (testing "All reviewers approved with high approval threshold"
    (let [reviews [{:author "alice" :state "APPROVED"}
                   {:author "bob" :state "APPROVED"}
                   {:author "charlie" :state "APPROVED"}
                   {:author "dave" :state "APPROVED"}]
          result (review/compute-review-status reviews 3)]
      (is (= :approved (:status result)))
      (is (= 4 (:approval-count result)))
      (is (= 3 (:required-approvals result))))))

(deftest compute-review-status-reviewer-approves-then-requests-changes-test
  (testing "Reviewer who first approved then requested changes"
    (let [reviews [{:author "alice" :state "APPROVED"}
                   {:author "bob" :state "APPROVED"}
                   {:author "bob" :state "CHANGES_REQUESTED"}]
          result (review/compute-review-status reviews 1)]
      ;; bob's changes-requested cancels their approval
      (is (= :changes-requested (:status result)))
      (is (= 1 (:approval-count result)))
      (is (contains? (set (:approvers result)) "alice"))
      (is (not (contains? (set (:approvers result)) "bob"))))))

(deftest compute-review-status-only-one-of-many-approved-test
  (testing "One approval when three are required yields pending"
    (let [reviews [{:author "alice" :state "APPROVED"}]
          result (review/compute-review-status reviews 3)]
      (is (= :pending (:status result)))
      (is (= 1 (:approval-count result)))
      (is (= 3 (:required-approvals result))))))

;------------------------------------------------------------------------------ Review State Transitions

(deftest review-state-transition-pending-to-approved-test
  (testing "Transition from pending to approved when approvals met"
    (let [pending-result (review/compute-review-status [] 1)
          approved-result (review/compute-review-status
                           [{:author "alice" :state "APPROVED"}] 1)]
      (is (= :pending (:status pending-result)))
      (is (= :approved (:status approved-result))))))

(deftest review-state-transition-pending-to-changes-requested-test
  (testing "Transition from pending to changes-requested"
    (let [pending-result (review/compute-review-status [] 1)
          changes-result (review/compute-review-status
                           [{:author "bob" :state "CHANGES_REQUESTED"}] 1)]
      (is (= :pending (:status pending-result)))
      (is (= :changes-requested (:status changes-result))))))

(deftest review-state-transition-changes-requested-to-approved-test
  (testing "Transition from changes-requested to approved after new approval"
    (let [;; Initially bob requests changes
          cr-result (review/compute-review-status
                      [{:author "bob" :state "CHANGES_REQUESTED"}] 1)
          ;; Then alice approves (bob's request still present but alice is a new approver)
          approved-result (review/compute-review-status
                            [{:author "bob" :state "CHANGES_REQUESTED"}
                             {:author "alice" :state "APPROVED"}] 1)]
      (is (= :changes-requested (:status cr-result)))
      ;; Still changes-requested because changes_requested takes precedence
      (is (= :changes-requested (:status approved-result))))))

(deftest review-state-transition-approved-to-changes-requested-test
  (testing "Transition from approved back to changes-requested"
    (let [approved-result (review/compute-review-status
                            [{:author "alice" :state "APPROVED"}] 1)
          reverted-result (review/compute-review-status
                            [{:author "alice" :state "APPROVED"}
                             {:author "bob" :state "CHANGES_REQUESTED"}] 1)]
      (is (= :approved (:status approved-result)))
      (is (= :changes-requested (:status reverted-result))))))

(deftest review-state-transition-incremental-approvals-test
  (testing "Progressive accumulation of approvals"
    (let [one-of-three (review/compute-review-status
                         [{:author "alice" :state "APPROVED"}] 3)
          two-of-three (review/compute-review-status
                         [{:author "alice" :state "APPROVED"}
                          {:author "bob" :state "APPROVED"}] 3)
          three-of-three (review/compute-review-status
                           [{:author "alice" :state "APPROVED"}
                            {:author "bob" :state "APPROVED"}
                            {:author "charlie" :state "APPROVED"}] 3)]
      (is (= :pending (:status one-of-three)))
      (is (= :pending (:status two-of-three)))
      (is (= :approved (:status three-of-three))))))

;------------------------------------------------------------------------------ Review Comment Extraction

(deftest extract-review-comments-test
  (testing "Extracts comments from CHANGES_REQUESTED reviews"
    (let [reviews [{:state "CHANGES_REQUESTED"
                    :comments [{:body "Fix this" :author "bob" :path "src/a.clj" :line 10}]}
                   {:state "APPROVED"
                    :comments [{:body "Looks good" :author "alice"}]}]
          comments (review/extract-review-comments reviews)]
      (is (= 1 (count comments)))
      (is (= "Fix this" (:body (first comments)))))))

(deftest extract-review-comments-empty-test
  (testing "No CHANGES_REQUESTED reviews yields empty comments"
    (let [reviews [{:state "APPROVED" :comments [{:body "OK"}]}]]
      (is (empty? (review/extract-review-comments reviews))))))

(deftest extract-review-comments-multiple-reviewers-test
  (testing "Extracts comments from multiple CHANGES_REQUESTED reviews"
    (let [reviews [{:state "CHANGES_REQUESTED"
                    :comments [{:body "Fix naming" :author "alice" :path "src/a.clj" :line 5}
                               {:body "Add docstring" :author "alice" :path "src/a.clj" :line 10}]}
                   {:state "CHANGES_REQUESTED"
                    :comments [{:body "Refactor this" :author "bob" :path "src/b.clj" :line 20}]}
                   {:state "APPROVED"
                    :comments [{:body "Nice work" :author "charlie"}]}]
          comments (review/extract-review-comments reviews)]
      (is (= 3 (count comments)))
      (is (= #{"alice" "bob"} (set (map :author comments)))))))

(deftest extract-review-comments-preserves-path-and-line-test
  (testing "Extracted comments preserve file path and line info"
    (let [reviews [{:state "CHANGES_REQUESTED"
                    :comments [{:body "Fix" :author "alice" :path "src/core.clj" :line 42}]}]
          comments (review/extract-review-comments reviews)]
      (is (= "src/core.clj" (:path (first comments))))
      (is (= 42 (:line (first comments)))))))

;------------------------------------------------------------------------------ Comment Tracking

(deftest create-comment-tracker-test
  (testing "Tracker initializes with empty state"
    (let [tracker (review/create-comment-tracker)]
      (is (empty? (:seen-ids @tracker)))
      (is (empty? (:comments @tracker))))))

(deftest track-comment-new-test
  (testing "New comment returns true and is tracked"
    (let [tracker (review/create-comment-tracker)
          comment {:id 1 :body "Hello" :author "alice"}
          result (review/track-comment! tracker comment)]
      (is (true? result))
      (is (= 1 (count (:comments @tracker))))
      (is (contains? (:seen-ids @tracker) 1)))))

(deftest track-comment-duplicate-test
  (testing "Duplicate comment returns false"
    (let [tracker (review/create-comment-tracker)
          comment {:id 1 :body "Hello" :author "alice"}]
      (review/track-comment! tracker comment)
      (let [result (review/track-comment! tracker comment)]
        (is (false? result))
        (is (= 1 (count (:comments @tracker)))
            "Duplicate should not be added again")))))

(deftest track-comment-hash-fallback-test
  (testing "Comments without :id use hash-based dedup"
    (let [tracker (review/create-comment-tracker)
          comment {:body "Hello" :author "alice"}]
      (is (true? (review/track-comment! tracker comment)))
      (is (false? (review/track-comment! tracker comment))))))

(deftest new-comments-filtering-test
  (testing "new-comments returns only unseen comments"
    (let [tracker (review/create-comment-tracker)
          c1 {:id 1 :body "First"}
          c2 {:id 2 :body "Second"}
          c3 {:id 3 :body "Third"}]
      ;; Pre-track c1
      (review/track-comment! tracker c1)
      (let [result (review/new-comments tracker [c1 c2 c3])]
        (is (= 2 (count result)))
        (is (= #{2 3} (set (map :id result))))))))

(deftest track-multiple-unique-comments-test
  (testing "Multiple unique comments are all tracked"
    (let [tracker (review/create-comment-tracker)
          comments (mapv (fn [i] {:id i :body (str "Comment " i) :author "alice"})
                         (range 1 6))]
      (doseq [c comments]
        (is (true? (review/track-comment! tracker c))))
      (is (= 5 (count (:comments @tracker))))
      (is (= 5 (count (:seen-ids @tracker)))))))

;------------------------------------------------------------------------------ Monitor Creation

(deftest create-review-monitor-test
  (testing "Monitor initializes with correct state"
    (let [dag-id (random-uuid)
          run-id (random-uuid)
          task-id (random-uuid)
          monitor (review/create-review-monitor dag-id run-id task-id 42 "/tmp/repo")]
      (is (= dag-id (:dag-id @monitor)))
      (is (= 42 (:pr-number @monitor)))
      (is (= :pending (:status @monitor)))
      (is (false? (:running? @monitor)))
      (is (= 1 (:required-approvals @monitor)))
      (is (some? (:comment-tracker @monitor))))))

(deftest create-review-monitor-custom-options-test
  (testing "Monitor respects custom options"
    (let [monitor (review/create-review-monitor
                    (random-uuid) (random-uuid) (random-uuid) 1 "/tmp"
                    :poll-interval-ms 5000
                    :required-approvals 3
                    :triage-policy :all)]
      (is (= 5000 (:poll-interval-ms @monitor)))
      (is (= 3 (:required-approvals @monitor)))
      (is (= :all (:triage-policy @monitor))))))

;------------------------------------------------------------------------------ Stop Monitor

(deftest stop-review-monitor-test
  (testing "stop-review-monitor sets running? to false"
    (let [monitor (review/create-review-monitor
                    (random-uuid) (random-uuid) (random-uuid) 1 "/tmp")]
      (swap! monitor assoc :running? true)
      (review/stop-review-monitor monitor)
      (is (false? (:running? @monitor))))))

;------------------------------------------------------------------------------ Mocked gh CLI: run-gh-command

(deftest run-gh-command-success-test
  (testing "Successful gh command returns dag/ok with trimmed output"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    {:exit 0 :out "  some output\n" :err ""})]
      (let [result (review/run-gh-command ["gh" "pr" "view" "42"] "/tmp/repo")]
        (is (dag/ok? result))
        (is (= "some output" (:output (:data result))))))))

(deftest run-gh-command-failure-test
  (testing "Failed gh command returns dag/err with error message"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    {:exit 1 :out "" :err "not found\n"})]
      (let [result (review/run-gh-command ["gh" "pr" "view" "999"] "/tmp/repo")]
        (is (dag/err? result))))))

(deftest run-gh-command-exception-test
  (testing "Exception during gh command returns dag/err"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    (throw (Exception. "Connection refused")))]
      (let [result (review/run-gh-command ["gh" "pr" "view" "42"] "/tmp/repo")]
        (is (dag/err? result))))))

;------------------------------------------------------------------------------ Mocked gh CLI: get-pr-reviews

(deftest get-pr-reviews-success-test
  (testing "get-pr-reviews returns raw JSON output on success"
    (let [json-output "{\"reviews\":[],\"reviewDecision\":\"APPROVED\"}"]
      (with-redefs [babashka.process/shell
                    (fn [& _args]
                      {:exit 0 :out json-output :err ""})]
        (let [result (review/get-pr-reviews "/tmp/repo" 42)]
          (is (dag/ok? result))
          (is (= json-output (:raw (:data result)))))))))

(deftest get-pr-reviews-failure-test
  (testing "get-pr-reviews propagates error on failure"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    {:exit 1 :out "" :err "Could not resolve to a PullRequest"})]
      (let [result (review/get-pr-reviews "/tmp/repo" 999)]
        (is (dag/err? result))))))

;------------------------------------------------------------------------------ Mocked gh CLI: get-pr-comments

(deftest get-pr-comments-success-test
  (testing "get-pr-comments returns raw JSON output on success"
    (let [json-output "{\"comments\":[{\"body\":\"LGTM\"}]}"]
      (with-redefs [babashka.process/shell
                    (fn [& _args]
                      {:exit 0 :out json-output :err ""})]
        (let [result (review/get-pr-comments "/tmp/repo" 42)]
          (is (dag/ok? result))
          (is (= json-output (:raw (:data result)))))))))

(deftest get-pr-comments-failure-test
  (testing "get-pr-comments propagates error on failure"
    (with-redefs [babashka.process/shell
                  (fn [& _args]
                    {:exit 1 :out "" :err "auth required"})]
      (let [result (review/get-pr-comments "/tmp/repo" 42)]
        (is (dag/err? result))))))

;------------------------------------------------------------------------------ Mocked poll-review-status

(deftest poll-review-status-with-mock-success-test
  (testing "poll-review-status updates monitor state on successful poll"
    (with-redefs [review/get-pr-reviews
                  (fn [_path _pr]
                    (dag/ok {:raw "{}"})) 
                  review/get-pr-comments
                  (fn [_path _pr]
                    (dag/ok {:raw "{}"}))]
      (let [monitor (review/create-review-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")]
        (is (= :pending (:status @monitor)))
        (let [result (review/poll-review-status monitor nil)]
          ;; Status is computed (defaults to :pending with no reviews parsed)
          (is (some? (:status result)))
          ;; Poll count incremented
          (is (= 1 (:polls @monitor)))
          ;; Last poll timestamp set
          (is (some? (:last-poll @monitor))))))))

(deftest poll-review-status-with-mock-failure-test
  (testing "poll-review-status returns :unknown on gh failure"
    (with-redefs [review/get-pr-reviews
                  (fn [_path _pr]
                    (dag/err :gh-command-failed "not found"))
                  review/get-pr-comments
                  (fn [_path _pr]
                    (dag/ok {:raw "{}"}))]
      (let [monitor (review/create-review-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")
            result (review/poll-review-status monitor nil)]
        (is (= :unknown (:status result)))
        (is (some? (:error result)))))))

(deftest poll-review-status-increments-poll-count-test
  (testing "Each poll increments the poll counter"
    (with-redefs [review/get-pr-reviews
                  (fn [_path _pr]
                    (dag/ok {:raw "{}"}))
                  review/get-pr-comments
                  (fn [_path _pr]
                    (dag/ok {:raw "{}"}))]
      (let [monitor (review/create-review-monitor
                      (random-uuid) (random-uuid) (random-uuid)
                      42 "/tmp/repo")]
        (review/poll-review-status monitor nil)
        (review/poll-review-status monitor nil)
        (review/poll-review-status monitor nil)
        (is (= 3 (:polls @monitor)))))))