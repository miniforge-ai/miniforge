(ns ai.miniforge.pr-lifecycle.review-monitor-test
  "Unit tests for review monitoring.

   Tests review status computation, comment tracking, review decision
   parsing, and monitor lifecycle. Does NOT test gh CLI calls."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.pr-lifecycle.review-monitor :as review]))

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