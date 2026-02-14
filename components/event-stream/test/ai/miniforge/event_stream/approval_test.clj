(ns ai.miniforge.event-stream.approval-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.approval :as approval]))

;; ============================================================================
;; Quorum approval tests
;; ============================================================================

(deftest quorum-approval-test
  (testing "quorum of 2 from 3 signers"
    (let [action-id (random-uuid)
          req (es/create-approval-request action-id ["alice" "bob" "carol"] 2)]
      ;; Initial state
      (is (= :pending (:approval/status req)))
      (is (= 2 (:approval/quorum req)))
      (is (= 3 (count (:approval/required-signers req))))

      ;; First approval — still pending
      (let [r1 (es/submit-approval req "alice" :approve)]
        (is (es/approval-succeeded? r1))
        (is (= :pending (:approval/status (:output r1))))
        (is (= 1 (count (:approval/signatures (:output r1)))))

        ;; Second approval — quorum met → approved
        (let [r2 (es/submit-approval (:output r1) "bob" :approve)]
          (is (es/approval-succeeded? r2))
          (is (= :approved (:approval/status (:output r2))))
          (is (= 2 (count (:approval/signatures (:output r2)))))))))

  (testing "quorum of 1 approves immediately"
    (let [req (es/create-approval-request (random-uuid) ["alice"] 1)
          r (es/submit-approval req "alice" :approve)]
      (is (= :approved (:approval/status (:output r)))))))

;; ============================================================================
;; Immediate rejection tests
;; ============================================================================

(deftest rejection-test
  (testing "any rejection immediately rejects"
    (let [req (es/create-approval-request (random-uuid) ["alice" "bob" "carol"] 3)
          r (es/submit-approval req "alice" :reject {:reason "Too risky"})]
      (is (es/approval-succeeded? r))
      (is (= :rejected (:approval/status (:output r))))
      (is (= "Too risky" (:reason (first (:approval/signatures (:output r)))))))))

;; ============================================================================
;; Expiry tests
;; ============================================================================

(deftest expiry-test
  (testing "check-approval-status detects expiry"
    (let [req (es/create-approval-request (random-uuid) ["alice"] 1
                                          {:expires-in-hours 0})]
      ;; With 0 hours, it should be expired immediately (or very soon)
      (Thread/sleep 10) ; ensure time passes
      (is (= :expired (es/check-approval-status req)))))

  (testing "non-expired request shows correct status"
    (let [req (es/create-approval-request (random-uuid) ["alice"] 1
                                          {:expires-in-hours 24})]
      (is (= :pending (es/check-approval-status req))))))

;; ============================================================================
;; Duplicate signer prevention tests
;; ============================================================================

(deftest duplicate-signer-test
  (testing "same signer cannot sign twice"
    (let [req (es/create-approval-request (random-uuid) ["alice" "bob"] 2)
          r1 (es/submit-approval req "alice" :approve)
          r2 (es/submit-approval (:output r1) "alice" :approve)]
      (is (es/approval-succeeded? r1))
      (is (es/approval-failed? r2))
      (is (string? (get-in r2 [:error :message]))))))

;; ============================================================================
;; Unauthorized signer tests
;; ============================================================================

(deftest unauthorized-signer-test
  (testing "unauthorized signer is rejected"
    (let [req (es/create-approval-request (random-uuid) ["alice" "bob"] 1)
          r (es/submit-approval req "eve" :approve)]
      (is (es/approval-failed? r))
      (is (string? (get-in r [:error :message]))))))

;; ============================================================================
;; Cancel tests
;; ============================================================================

(deftest cancel-test
  (testing "pending request can be cancelled"
    (let [req (es/create-approval-request (random-uuid) ["alice"] 1)
          r (es/cancel-approval req "admin" "No longer needed")]
      (is (es/approval-succeeded? r))
      (is (= :cancelled (:approval/status (:output r))))
      (is (= "admin" (:approval/cancelled-by (:output r))))))

  (testing "non-pending request cannot be cancelled"
    (let [req (es/create-approval-request (random-uuid) ["alice"] 1)
          approved (es/submit-approval req "alice" :approve)
          r (es/cancel-approval (:output approved) "admin" "Too late")]
      (is (es/approval-failed? r)))))

;; ============================================================================
;; Approval manager tests
;; ============================================================================

(deftest approval-manager-test
  (testing "store and retrieve approval"
    (let [mgr (es/create-approval-manager)
          req (es/create-approval-request (random-uuid) ["alice"] 1)]
      (es/store-approval! mgr req)
      (let [retrieved (es/get-approval mgr (:approval/id req))]
        (is (= (:approval/id req) (:approval/id retrieved)))
        (is (= :pending (:approval/status retrieved))))))

  (testing "update approval"
    (let [mgr (es/create-approval-manager)
          req (es/create-approval-request (random-uuid) ["alice"] 1)]
      (es/store-approval! mgr req)
      (let [r (es/submit-approval req "alice" :approve)]
        (es/update-approval! mgr (:output r))
        (let [updated (es/get-approval mgr (:approval/id req))]
          (is (= :approved (:approval/status updated)))))))

  (testing "list approvals"
    (let [mgr (es/create-approval-manager)
          req1 (es/create-approval-request (random-uuid) ["alice"] 1)
          req2 (es/create-approval-request (random-uuid) ["bob"] 1)]
      (es/store-approval! mgr req1)
      (es/store-approval! mgr req2)
      (is (= 2 (count (es/list-approvals mgr)))))))

;; ============================================================================
;; Event emission tests
;; ============================================================================

(deftest approval-events-test
  (testing "approval event constructors create correct events"
    (let [stream (es/create-event-stream {:sinks []})
          wf-id (random-uuid)
          approval-id (random-uuid)
          action-id (random-uuid)]

      ;; approval/requested
      (let [e (approval/approval-requested stream wf-id approval-id action-id ["alice" "bob"])]
        (is (= :approval/requested (:event/type e)))
        (is (= approval-id (:approval/id e)))
        (is (= action-id (:approval/action-id e))))

      ;; approval/signed
      (let [e (approval/approval-signed stream wf-id approval-id "alice" :approve)]
        (is (= :approval/signed (:event/type e)))
        (is (= "alice" (:approval/signer e)))
        (is (= :approve (:approval/decision e))))

      ;; approval/completed
      (let [e (approval/approval-completed stream wf-id approval-id :approved)]
        (is (= :approval/completed (:event/type e)))
        (is (= :approved (:approval/final-status e))))

      ;; approval/expired
      (let [e (approval/approval-expired stream wf-id approval-id)]
        (is (= :approval/expired (:event/type e)))
        (is (= approval-id (:approval/id e)))))))

;; ============================================================================
;; Control action integration tests
;; ============================================================================

(deftest control-action-with-approval-test
  (testing "gate-override requires approval"
    (is (true? (es/requires-approval? :gate-override)))
    (is (true? (es/requires-approval? :budget-escalation))))

  (testing "regular actions do not require approval"
    (is (false? (es/requires-approval? :pause)))
    (is (false? (es/requires-approval? :resume))))

  (testing "execute-with-approval creates approval for gate-override"
    (let [stream (es/create-event-stream {:sinks []})
          action (es/create-control-action
                  :gate-override
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "admin" :role :admin})
          result (es/execute-control-action-with-approval!
                  stream action
                  (fn [_] {:overridden true})
                  {:required-signers ["alice" "bob"] :quorum 2})]
      (is (= :awaiting-approval (:status result)))
      (is (uuid? (:approval/id result)))))

  (testing "execute-with-approval runs directly for regular actions"
    (let [stream (es/create-event-stream {:sinks []})
          action (es/create-control-action
                  :pause
                  {:target-type :workflow :target-id (random-uuid)}
                  {:principal "admin" :role :admin})
          result (es/execute-control-action-with-approval!
                  stream action
                  (fn [_] {:paused true}))]
      (is (= :success (:status result))))))
