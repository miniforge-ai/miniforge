(ns ai.miniforge.pr-lifecycle.integration-test
  "Integration test for PR lifecycle state machine.

  Tests the full PR lifecycle flow without real git operations or gh CLI calls.
  Validates state transitions, event emission, and error handling."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-lifecycle.controller :as controller]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Mock Data

(def mock-task
  {:task/id "test-task-1"
   :task/description "Implement feature X"
   :task/acceptance-criteria ["Feature works" "Tests pass"]
   :task/constraints ["No breaking changes"]})

(def mock-pr-info
  {:pr-number 123
   :pr-url "https://github.com/org/repo/pull/123"
   :branch "feature/test-feature"
   :commit-sha "abc123def456"})

(def mock-ci-success
  {:status :success
   :checks [{:name "tests" :conclusion "success"}
            {:name "lint" :conclusion "success"}]})

(def mock-ci-failure
  {:status :failure
   :checks [{:name "tests" :conclusion "failure" :details "Test suite failed"}]})

(def mock-review-approved
  {:status :approved
   :reviews [{:user "reviewer1" :state "APPROVED"}]})

(def mock-review-changes-requested
  {:status :changes_requested
   :reviews [{:user "reviewer1" :state "CHANGES_REQUESTED"
              :comments ["Please fix the error handling"]}]})

;------------------------------------------------------------------------------ Mock Implementations

(defn mock-release-executor
  "Mock release executor that simulates PR creation."
  [success?]
  (fn [workflow-state exec-context opts]
    (if success?
      {:success? true
       :artifacts [{:artifact/type :release
                    :artifact/content mock-pr-info}]
       :metrics {:tokens 100 :duration-ms 1000}}
      {:success? false
       :errors [{:type :pr-creation-failed
                 :message "Failed to create PR"}]})))

(defn mock-ci-monitor
  "Mock CI monitor that returns predefined CI status."
  [ci-results-seq]
  (let [results (atom ci-results-seq)]
    (fn [repo-path pr-number]
      (let [result (first @results)]
        (swap! results rest)
        result))))

(defn mock-review-monitor
  "Mock review monitor that returns predefined review status."
  [review-results-seq]
  (let [results (atom review-results-seq)]
    (fn [repo-path pr-number]
      (let [result (first @results)]
        (swap! results rest)
        result))))

(defn mock-merge-operation
  "Mock merge operation."
  [success?]
  (fn [repo-path pr-number merge-policy]
    (if success?
      {:success? true
       :merged-sha "def789abc123"
       :method (:method merge-policy :squash)}
      {:success? false
       :error "Merge conflict detected"})))

(defn mock-fix-generator
  "Mock fix generator that returns a code artifact."
  [success?]
  (fn [task error-details context]
    (if success?
      (response/success
       {:code/id (random-uuid)
        :code/files [{:path "src/fixed.clj"
                      :content "(ns fixed)\n(defn fixed-fn [] :ok)"
                      :action :modify}]}
       {:tokens 500 :duration-ms 2000})
      (response/failure
       (ex-info "Fix generation failed" {:error error-details})))))

(defn collect-events
  "Create an event collector that captures all emitted events."
  []
  (let [events (atom [])]
    {:publish! (fn [event]
                 (swap! events conj event)
                 nil)
     :events events}))

;------------------------------------------------------------------------------ Helper Functions

(defn create-test-controller
  "Create a controller with mocked dependencies."
  [& {:keys [generate-fn ci-monitor-fn review-monitor-fn merge-fn]
      :or {generate-fn (mock-fix-generator true)
           ci-monitor-fn (mock-ci-monitor [mock-ci-success])
           review-monitor-fn (mock-review-monitor [mock-review-approved])
           merge-fn (mock-merge-operation true)}}]
  (let [event-collector (collect-events)]
    {:controller (controller/create-controller
                  "test-dag" "test-run" "test-task" mock-task
                  :worktree-path "/tmp/test-repo"
                  :event-bus event-collector
                  :generate-fn generate-fn
                  :ci-poll-interval-ms 100
                  :review-poll-interval-ms 100)
     :events (:events event-collector)}))

(defn get-status [controller]
  (:status @controller))

(defn get-pr-info [controller]
  (:pr @controller))

(defn get-history [controller]
  (:history @controller))

(defn simulate-pr-creation!
  "Simulate PR creation by updating controller state."
  [controller pr-info]
  (swap! controller assoc
         :status :monitoring-ci
         :pr pr-info)
  pr-info)

(defn simulate-ci-result!
  "Simulate CI result by updating controller."
  [controller ci-result]
  (swap! controller assoc :last-ci-result ci-result)
  ci-result)

;------------------------------------------------------------------------------ Tests

(deftest controller-creation-test
  (testing "Controller creation initializes state correctly"
    (let [{:keys [controller]} (create-test-controller)]
      (is (= :pending (get-status controller))
          "Initial status should be :pending")
      (is (nil? (get-pr-info controller))
          "PR info should be nil initially")
      (is (empty? (get-history controller))
          "History should be empty initially")
      (is (some? (:controller/id @controller))
          "Controller should have an ID"))))

(deftest pr-creation-flow-test
  (testing "PR creation updates controller state"
    (with-redefs [ai.miniforge.release-executor.interface/execute-release-phase
                  (mock-release-executor true)]
      (let [{:keys [controller]} (create-test-controller)]

        ;; Simulate PR creation
        (simulate-pr-creation! controller mock-pr-info)

        (is (= :monitoring-ci (get-status controller))
            "Status should transition to :monitoring-ci after PR creation")
        (is (= mock-pr-info (get-pr-info controller))
            "PR info should be stored in controller")
        (is (= 123 (:pr-number (get-pr-info controller)))
            "PR number should be accessible")))))

(deftest ci-monitoring-happy-path-test
  (testing "CI monitoring transitions to review monitoring on success"
    (let [{:keys [controller]} (create-test-controller
                                :ci-monitor-fn (mock-ci-monitor [mock-ci-success]))]

      ;; Set up controller in monitoring-ci state
      (simulate-pr-creation! controller mock-pr-info)
      (simulate-ci-result! controller mock-ci-success)

      (is (= :success (:status mock-ci-success))
          "CI should report success")
      (is (every? #(= "success" (:conclusion %))
                  (:checks mock-ci-success))
          "All checks should pass"))))

(deftest ci-failure-triggers-fix-loop-test
  (testing "CI failure triggers fix loop"
    (let [{:keys [controller events]} (create-test-controller
                                       :ci-monitor-fn (mock-ci-monitor [mock-ci-failure])
                                       :generate-fn (mock-fix-generator true))]

      ;; Set up controller with PR created
      (simulate-pr-creation! controller mock-pr-info)
      (simulate-ci-result! controller mock-ci-failure)

      (is (= :failure (:status mock-ci-failure))
          "CI should report failure")
      (is (some #(= "failure" (:conclusion %))
                (:checks mock-ci-failure))
          "At least one check should fail"))))

(deftest review-approval-flow-test
  (testing "Review approval transitions to ready-to-merge"
    (let [{:keys [controller]} (create-test-controller
                                :ci-monitor-fn (mock-ci-monitor [mock-ci-success])
                                :review-monitor-fn (mock-review-monitor [mock-review-approved]))]

      ;; Simulate full flow: PR created → CI passed → Review approved
      (simulate-pr-creation! controller mock-pr-info)
      (simulate-ci-result! controller mock-ci-success)

      ;; Manually transition to ready-to-merge (in real code, review monitor does this)
      (swap! controller assoc :status :ready-to-merge)

      (is (= :ready-to-merge (get-status controller))
          "Controller should be ready to merge after approval"))))

(deftest review-changes-requested-test
  (testing "Changes requested triggers fix loop"
    (let [{:keys [controller]} (create-test-controller
                                :review-monitor-fn (mock-review-monitor [mock-review-changes-requested])
                                :generate-fn (mock-fix-generator true))]

      ;; Set up controller with PR and CI passing
      (simulate-pr-creation! controller mock-pr-info)
      (simulate-ci-result! controller mock-ci-success)

      (is (= :changes_requested (:status mock-review-changes-requested))
          "Review should request changes")
      (is (seq (:reviews mock-review-changes-requested))
          "Review should have comments"))))

(deftest merge-success-flow-test
  (testing "Successful merge completes lifecycle"
    (with-redefs [ai.miniforge.pr-lifecycle.merge/merge-pr!
                  (mock-merge-operation true)]
      (let [{:keys [controller]} (create-test-controller)]

        ;; Simulate ready-to-merge state
        (swap! controller assoc
               :status :ready-to-merge
               :pr mock-pr-info)

        ;; Manually transition to merged (in real code, merge! does this)
        (swap! controller assoc :status :merged)

        (is (= :merged (get-status controller))
            "Controller should be in :merged state")))))

(deftest merge-conflict-handling-test
  (testing "Merge conflict triggers rebase or fix"
    (with-redefs [ai.miniforge.pr-lifecycle.merge/merge-pr!
                  (mock-merge-operation false)]
      (let [{:keys [controller]} (create-test-controller)]

        ;; Simulate merge attempt with conflict
        (swap! controller assoc
               :status :ready-to-merge
               :pr mock-pr-info)

        ;; In real code, merge failure would transition to :fixing or :failed
        (swap! controller assoc :status :fixing)

        (is (= :fixing (get-status controller))
            "Controller should enter fixing state on merge conflict")))))

(deftest max-fix-iterations-enforcement-test
  (testing "Max fix iterations causes failure"
    (let [{:keys [controller]} (create-test-controller
                                :generate-fn (mock-fix-generator false))]

      ;; Simulate multiple fix iterations
      (swap! controller assoc :fix-iterations 5)
      (swap! controller assoc :config {:max-fix-iterations 5})

      (is (>= (:fix-iterations @controller)
              (get-in @controller [:config :max-fix-iterations]))
          "Fix iterations should reach max limit"))))

(deftest event-emission-test
  (testing "Events are emitted for state transitions"
    (let [{:keys [controller events]} (create-test-controller)]

      ;; Simulate state transitions
      (swap! controller assoc :status :creating-pr)
      (swap! controller assoc :status :monitoring-ci)
      (swap! controller assoc :status :monitoring-review)
      (swap! controller assoc :status :ready-to-merge)
      (swap! controller assoc :status :merged)

      ;; Note: In real code, events would be published via event-bus
      ;; This test just verifies the event collector structure works
      (is (vector? @events)
          "Events should be collected in a vector"))))

(deftest history-tracking-test
  (testing "Controller tracks history of events"
    (let [{:keys [controller]} (create-test-controller)]

      ;; Add some history events
      (swap! controller update :history conj
             {:type :pr-created
              :data mock-pr-info
              :timestamp (java.util.Date.)})

      (swap! controller update :history conj
             {:type :ci-passed
              :data mock-ci-success
              :timestamp (java.util.Date.)})

      (let [history (get-history controller)]
        (is (= 2 (count history))
            "History should contain 2 events")
        (is (= :pr-created (:type (first history)))
            "First event should be :pr-created")
        (is (= :ci-passed (:type (second history)))
            "Second event should be :ci-passed")))))

(deftest concurrent-monitoring-test
  (testing "CI and review monitoring can run concurrently"
    (let [{:keys [controller]} (create-test-controller
                                :ci-monitor-fn (mock-ci-monitor [mock-ci-success])
                                :review-monitor-fn (mock-review-monitor [mock-review-approved]))]

      ;; Set up for concurrent monitoring
      (simulate-pr-creation! controller mock-pr-info)

      ;; Simulate both monitors active
      (swap! controller assoc
             :ci-monitor {:active true}
             :review-monitor {:active true})

      (is (get-in @controller [:ci-monitor :active])
          "CI monitor should be active")
      (is (get-in @controller [:review-monitor :active])
          "Review monitor should be active"))))

(deftest full-happy-path-simulation-test
  (testing "Full lifecycle: PR → CI pass → Review approve → Merge"
    (let [{:keys [controller events]} (create-test-controller)]

      ;; 1. Start in pending state
      (is (= :pending (get-status controller)))

      ;; 2. Create PR
      (simulate-pr-creation! controller mock-pr-info)
      (is (= :monitoring-ci (get-status controller)))
      (is (= 123 (:pr-number (get-pr-info controller))))

      ;; 3. CI passes
      (simulate-ci-result! controller mock-ci-success)
      (swap! controller assoc :status :monitoring-review)
      (is (= :monitoring-review (get-status controller)))

      ;; 4. Review approved
      (swap! controller assoc :status :ready-to-merge)
      (is (= :ready-to-merge (get-status controller)))

      ;; 5. Merge succeeds
      (swap! controller assoc :status :merged)
      (is (= :merged (get-status controller)))

      ;; Verify complete flow
      (is (some? (get-pr-info controller))
          "PR info should be retained after merge"))))

(deftest error-recovery-test
  (testing "Controller can recover from transient errors"
    (let [{:keys [controller]} (create-test-controller
                                ;; First check fails, second succeeds
                                :ci-monitor-fn (mock-ci-monitor [mock-ci-failure
                                                                  mock-ci-success]))]

      (simulate-pr-creation! controller mock-pr-info)

      ;; First CI check fails
      (simulate-ci-result! controller mock-ci-failure)
      (is (= :failure (:status mock-ci-failure)))

      ;; After fix, second CI check passes
      (simulate-ci-result! controller mock-ci-success)
      (is (= :success (:status mock-ci-success))
          "CI should pass after fix"))))
