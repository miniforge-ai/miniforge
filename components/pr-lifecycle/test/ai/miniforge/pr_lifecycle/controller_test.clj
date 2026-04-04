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

(ns ai.miniforge.pr-lifecycle.controller-test
  "Unit tests for the PR lifecycle controller state machine.

   Tests controller creation, state initialization, configuration,
   state machine transitions, invalid transition rejection,
   fix iteration enforcement, history tracking, and the extracted
   PR creation step functions."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.pr-lifecycle.controller :as controller]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.release-executor.interface :as release]))

;------------------------------------------------------------------------------ Test Data

(def test-task
  {:task/id "task-abc"
   :task/title "Implement feature X"
   :task/description "Add the feature"
   :task/acceptance-criteria ["Tests pass" "No lint errors"]
   :task/constraints ["No breaking changes"]})

(defn make-event-collector
  "Create a minimal event bus that collects published events."
  []
  (let [events (atom [])]
    {:publish! (fn [event] (swap! events conj event) nil)
     :events events}))

;------------------------------------------------------------------------------ Controller Creation

(deftest create-controller-initial-state-test
  (testing "Controller initializes with :pending status"
    (let [ctrl (controller/create-controller
                 "dag-1" "run-1" "task-1" test-task
                 :worktree-path "/tmp/repo")]
      (is (= :pending (:status @ctrl)))
      (is (nil? (:pr @ctrl)))
      (is (= 0 (:fix-iterations @ctrl)))
      (is (= 0 (:ci-retries @ctrl)))
      (is (empty? (:history @ctrl)))
      (is (inst? (:created-at @ctrl)))
      (is (inst? (:updated-at @ctrl))))))

(deftest create-controller-ids-test
  (testing "Controller stores DAG/run/task IDs"
    (let [ctrl (controller/create-controller
                 "dag-1" "run-1" "task-1" test-task
                 :worktree-path "/tmp")]
      (is (= "dag-1" (:dag/id @ctrl)))
      (is (= "run-1" (:run/id @ctrl)))
      (is (= "task-1" (:task/id @ctrl)))
      (is (uuid? (:controller/id @ctrl))))))

(deftest create-controller-stores-task-test
  (testing "Controller stores the task definition"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= test-task (:task @ctrl))))))

(deftest create-controller-default-config-test
  (testing "Controller uses default configuration values"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp/repo")]
      (is (= "/tmp/repo" (get-in @ctrl [:config :worktree-path])))
      (is (= 5 (get-in @ctrl [:config :max-fix-iterations])))
      (is (= 30000 (get-in @ctrl [:config :ci-poll-interval-ms])))
      (is (= 30000 (get-in @ctrl [:config :review-poll-interval-ms])))
      (is (= merge/default-merge-policy (get-in @ctrl [:config :merge-policy])))
      (is (true? (get-in @ctrl [:config :auto-resolve-comments]))))))

(deftest create-controller-custom-config-test
  (testing "Controller accepts custom configuration"
    (let [custom-policy {:method :rebase :require-ci-green? false}
          ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/custom/path"
                 :max-fix-iterations 10
                 :ci-poll-interval-ms 5000
                 :review-poll-interval-ms 10000
                 :merge-policy custom-policy
                 :auto-resolve-comments false)]
      (is (= "/custom/path" (get-in @ctrl [:config :worktree-path])))
      (is (= 10 (get-in @ctrl [:config :max-fix-iterations])))
      (is (= 5000 (get-in @ctrl [:config :ci-poll-interval-ms])))
      (is (= 10000 (get-in @ctrl [:config :review-poll-interval-ms])))
      (is (= custom-policy (get-in @ctrl [:config :merge-policy])))
      (is (false? (get-in @ctrl [:config :auto-resolve-comments]))))))

(deftest create-controller-with-dependencies-test
  (testing "Controller stores event-bus, logger, generate-fn"
    (let [bus (make-event-collector)
          gen-fn (fn [& _] nil)
          ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :event-bus bus
                 :generate-fn gen-fn)]
      (is (= bus (:event-bus @ctrl)))
      (is (= gen-fn (:generate-fn @ctrl))))))

;------------------------------------------------------------------------------ Config Validation

(deftest create-controller-nil-worktree-path-test
  (testing "Controller accepts nil worktree-path (stored as nil in config)"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task)]
      (is (nil? (get-in @ctrl [:config :worktree-path])))
      (is (= :pending (:status @ctrl))))))

(deftest create-controller-merge-policy-validation-test
  (testing "Custom merge policy is stored verbatim without defaults merging"
    (let [partial-policy {:method :rebase}
          ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :merge-policy partial-policy)]
      (is (= partial-policy (get-in @ctrl [:config :merge-policy])))
      (is (nil? (get-in @ctrl [:config :merge-policy :require-ci-green?]))
          "Partial policy should not be merged with defaults"))))

(deftest create-controller-zero-max-iterations-test
  (testing "Controller allows zero max-fix-iterations"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 0)]
      (is (= 0 (get-in @ctrl [:config :max-fix-iterations]))))))

(deftest create-controller-config-keys-complete-test
  (testing "Config map contains exactly the expected keys"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= #{:worktree-path :merge-policy :max-fix-iterations
               :ci-poll-interval-ms :review-poll-interval-ms
               :auto-resolve-comments}
             (set (keys (:config @ctrl))))))))

;------------------------------------------------------------------------------ State Machine Transitions via update-status!

(deftest update-status-returns-new-status-test
  (testing "update-status! returns the new status value"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= :creating-pr (controller/update-status! ctrl :creating-pr))))))

(deftest update-status-updates-timestamp-test
  (testing "update-status! updates the :updated-at timestamp"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")
          before-ts (:updated-at @ctrl)]
      ;; Small sleep to ensure timestamp differs
      (Thread/sleep 5)
      (controller/update-status! ctrl :monitoring-ci)
      (let [after-ts (:updated-at @ctrl)]
        (is (not= before-ts after-ts))
        (is (.after ^java.util.Date after-ts ^java.util.Date before-ts))))))

(deftest state-machine-full-happy-path-test
  (testing "State transitions through the full happy path: pending -> creating-pr -> monitoring-ci -> monitoring-review -> ready-to-merge -> merging -> merged"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= :pending (:status @ctrl)))

      (controller/update-status! ctrl :creating-pr)
      (is (= :creating-pr (:status @ctrl)))

      (controller/update-status! ctrl :monitoring-ci)
      (is (= :monitoring-ci (:status @ctrl)))

      (controller/update-status! ctrl :monitoring-review)
      (is (= :monitoring-review (:status @ctrl)))

      (controller/update-status! ctrl :ready-to-merge)
      (is (= :ready-to-merge (:status @ctrl)))

      ;; The controller uses :merging internally via attempt-merge!
      (controller/update-status! ctrl :merged)
      (is (= :merged (:status @ctrl))))))

(deftest state-machine-ci-failure-fix-loop-test
  (testing "State transitions through CI failure/fix loop: pending -> creating-pr -> monitoring-ci -> fixing -> monitoring-ci -> monitoring-review -> merged"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= :pending (:status @ctrl)))

      (controller/update-status! ctrl :creating-pr)
      (is (= :creating-pr (:status @ctrl)))

      (controller/update-status! ctrl :monitoring-ci)
      (is (= :monitoring-ci (:status @ctrl)))

      ;; CI failure triggers fix
      (controller/update-status! ctrl :fixing)
      (is (= :fixing (:status @ctrl)))

      ;; Fix pushed, back to CI monitoring
      (controller/update-status! ctrl :monitoring-ci)
      (is (= :monitoring-ci (:status @ctrl)))

      ;; CI passes, move to review
      (controller/update-status! ctrl :monitoring-review)
      (is (= :monitoring-review (:status @ctrl)))

      ;; Review approved, merge
      (controller/update-status! ctrl :ready-to-merge)
      (is (= :ready-to-merge (:status @ctrl)))

      (controller/update-status! ctrl :merged)
      (is (= :merged (:status @ctrl))))))

(deftest state-machine-review-changes-requested-loop-test
  (testing "State transitions through review changes requested loop"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (controller/update-status! ctrl :creating-pr)
      (controller/update-status! ctrl :monitoring-ci)
      (controller/update-status! ctrl :monitoring-review)

      ;; Reviewer requests changes -> fix loop
      (controller/update-status! ctrl :fixing)
      (is (= :fixing (:status @ctrl)))

      ;; Fix pushed, re-run CI
      (controller/update-status! ctrl :monitoring-ci)
      (is (= :monitoring-ci (:status @ctrl)))

      ;; CI passes, back to review
      (controller/update-status! ctrl :monitoring-review)
      (is (= :monitoring-review (:status @ctrl)))

      ;; Approved this time
      (controller/update-status! ctrl :ready-to-merge)
      (controller/update-status! ctrl :merged)
      (is (= :merged (:status @ctrl))))))

(deftest state-machine-failure-terminal-test
  (testing ":failed is a terminal state - controller records failure"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (controller/update-status! ctrl :monitoring-ci)
      (controller/update-status! ctrl :failed)
      (is (= :failed (:status @ctrl))))))

;------------------------------------------------------------------------------ Invalid / Unexpected State Transitions
;;
;; NOTE: The current controller uses a simple atom and does not enforce
;; state transition guards. These tests document that arbitrary status
;; values are stored as-is (they are NOT rejected). This is important
;; for understanding the controller's current contract and for detecting
;; regressions if transition guards are added in the future.

(deftest invalid-status-value-stored-as-is-test
  (testing "Controller stores arbitrary status values (no guard)"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      ;; An invalid status keyword is stored without rejection
      (controller/update-status! ctrl :bogus-status)
      (is (= :bogus-status (:status @ctrl))
          "Controller currently accepts any keyword as status"))))

(deftest backward-transition-not-rejected-test
  (testing "Backward transitions are not rejected (no guard)"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (controller/update-status! ctrl :monitoring-ci)
      (controller/update-status! ctrl :pending)
      (is (= :pending (:status @ctrl))
          "Controller allows going back to :pending from :monitoring-ci"))))

(deftest transition-from-terminal-state-not-rejected-test
  (testing "Transitioning from :merged (terminal) is not rejected (no guard)"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (controller/update-status! ctrl :merged)
      (controller/update-status! ctrl :monitoring-ci)
      (is (= :monitoring-ci (:status @ctrl))
          "Controller allows transition away from terminal :merged state"))))

(deftest transition-from-failed-state-not-rejected-test
  (testing "Transitioning from :failed (terminal) is not rejected (no guard)"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (controller/update-status! ctrl :failed)
      (controller/update-status! ctrl :creating-pr)
      (is (= :creating-pr (:status @ctrl))
          "Controller allows transition away from terminal :failed state"))))

;------------------------------------------------------------------------------ add-history! Function

(deftest add-history-appends-event-test
  (testing "add-history! appends an event with type, data, and timestamp"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (controller/add-history! ctrl :pr-created {:pr-id 42})
      (is (= 1 (count (:history @ctrl))))
      (let [event (first (:history @ctrl))]
        (is (= :pr-created (:type event)))
        (is (= {:pr-id 42} (:data event)))
        (is (inst? (:timestamp event)))))))

(deftest add-history-returns-nil-test
  (testing "add-history! returns nil"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (nil? (controller/add-history! ctrl :test-event {}))))))

(deftest add-history-preserves-order-test
  (testing "add-history! preserves chronological order"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (controller/add-history! ctrl :first {:n 1})
      (controller/add-history! ctrl :second {:n 2})
      (controller/add-history! ctrl :third {:n 3})
      (is (= [:first :second :third]
             (mapv :type (:history @ctrl)))))))

;------------------------------------------------------------------------------ Fix Iteration Enforcement

(deftest handle-ci-failure-max-iterations-test
  (testing "handle-ci-failure! throws when max iterations exceeded"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 3)]
      ;; Simulate having already used all iterations
      (swap! ctrl assoc :fix-iterations 3)
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Max fix iterations exceeded"
            (controller/handle-ci-failure! ctrl "some ci logs"))))))

(deftest handle-review-feedback-max-iterations-test
  (testing "handle-review-feedback! throws when max iterations exceeded"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 2)]
      (swap! ctrl assoc :fix-iterations 2)
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Max fix iterations exceeded"
            (controller/handle-review-feedback! ctrl [{:body "Fix"}]))))))

(deftest handle-ci-failure-increments-iteration-before-max-test
  (testing "handle-ci-failure! transitions to :fixing and increments counter"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 5
                 :generate-fn (fn [& _] nil))]
      ;; We need to set up PR info for the fix loop
      (swap! ctrl assoc
             :pr {:pr/id 123 :pr/branch "feat/x" :pr/head-sha "abc"}
             :fix-iterations 0)
      ;; The actual fix-ci-failure call will fail because generate-fn
      ;; returns nil, but we can verify the state transitions happened
      (try
        (controller/handle-ci-failure! ctrl "CI logs here")
        (catch Exception _e
          ;; Expected - fix generation will fail
          nil))
      ;; Verify state was updated before the fix attempt
      (is (= :fixing (:status @ctrl)))
      (is (= 1 (:fix-iterations @ctrl))))))

(deftest handle-ci-failure-sets-failed-on-max-test
  (testing "handle-ci-failure! sets :failed status when at max"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 3)]
      (swap! ctrl assoc :fix-iterations 3)
      (try
        (controller/handle-ci-failure! ctrl "logs")
        (catch clojure.lang.ExceptionInfo _e nil))
      (is (= :failed (:status @ctrl))))))

(deftest handle-ci-failure-records-history-on-max-exceeded-test
  (testing "handle-ci-failure! records :max-fix-iterations-exceeded in history"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 2)]
      (swap! ctrl assoc :fix-iterations 2)
      (try
        (controller/handle-ci-failure! ctrl "logs")
        (catch clojure.lang.ExceptionInfo _e nil))
      (is (some #(= :max-fix-iterations-exceeded (:type %)) (:history @ctrl))))))

(deftest handle-ci-failure-zero-max-iterations-test
  (testing "handle-ci-failure! immediately throws when max-fix-iterations is 0"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp"
                 :max-fix-iterations 0)]
      (is (thrown-with-msg?
            clojure.lang.ExceptionInfo
            #"Max fix iterations exceeded"
            (controller/handle-ci-failure! ctrl "logs"))))))

;------------------------------------------------------------------------------ State Manipulation (via atom)

(deftest controller-status-transitions-test
  (testing "Status can be manually transitioned for testing"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= :pending (:status @ctrl)))

      (swap! ctrl assoc :status :creating-pr)
      (is (= :creating-pr (:status @ctrl)))

      (swap! ctrl assoc :status :monitoring-ci)
      (is (= :monitoring-ci (:status @ctrl)))

      (swap! ctrl assoc :status :monitoring-review)
      (is (= :monitoring-review (:status @ctrl)))

      (swap! ctrl assoc :status :ready-to-merge)
      (is (= :ready-to-merge (:status @ctrl)))

      (swap! ctrl assoc :status :merged)
      (is (= :merged (:status @ctrl))))))

(deftest controller-pr-info-storage-test
  (testing "PR info can be stored and retrieved"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")
          pr-info {:pr/id 42
                   :pr/url "https://github.com/org/repo/pull/42"
                   :pr/branch "feat/bar"
                   :pr/head-sha "def456"}]
      (swap! ctrl assoc :pr pr-info)
      (is (= 42 (get-in @ctrl [:pr :pr/id])))
      (is (= "feat/bar" (get-in @ctrl [:pr :pr/branch]))))))

(deftest controller-history-accumulation-test
  (testing "History events accumulate correctly"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (swap! ctrl update :history conj
             {:type :pr-created :data {:pr-id 1} :timestamp (java.util.Date.)})
      (swap! ctrl update :history conj
             {:type :ci-passed :data {} :timestamp (java.util.Date.)})
      (swap! ctrl update :history conj
             {:type :merged :data {:sha "abc"} :timestamp (java.util.Date.)})
      (is (= 3 (count (:history @ctrl))))
      (is (= [:pr-created :ci-passed :merged]
             (mapv :type (:history @ctrl)))))))

(deftest controller-fix-iteration-counter-test
  (testing "Fix iteration counter increments correctly"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (= 0 (:fix-iterations @ctrl)))
      (swap! ctrl update :fix-iterations inc)
      (is (= 1 (:fix-iterations @ctrl)))
      (swap! ctrl update :fix-iterations inc)
      (swap! ctrl update :fix-iterations inc)
      (is (= 3 (:fix-iterations @ctrl))))))

(deftest controller-monitors-nil-initially-test
  (testing "CI and review monitors are nil initially"
    (let [ctrl (controller/create-controller
                 "dag" "run" "task" test-task
                 :worktree-path "/tmp")]
      (is (nil? (:ci-monitor @ctrl)))
      (is (nil? (:review-monitor @ctrl))))))

;------------------------------------------------------------------------------ Extracted PR creation steps

(defn make-controller
  "Create a test controller with minimal config."
  ([] (make-controller {}))
  ([overrides]
   (let [ctrl (controller/create-controller
                "dag-1" "run-1" "task-00000001" test-task
                :worktree-path "/tmp/repo")]
     (when (seq overrides)
       (swap! ctrl merge overrides))
     ctrl)))

;; fail-controller!

(deftest fail-controller-sets-status-and-returns-err
  (testing "fail-controller! sets :failed status and returns a DAG error"
    (let [ctrl (make-controller)]
      (is (= :pending (:status @ctrl)))
      (let [result (controller/fail-controller! ctrl :some-error "boom")]
        (is (= :failed (:status @ctrl)))
        (is (dag/err? result))
        (is (= :some-error (get-in result [:error :code])))))))

;; create-and-checkout-branch!

(deftest create-and-checkout-branch-success
  (testing "Returns branch result on success"
    (let [ctrl (make-controller)]
      (with-redefs [release/create-branch! (fn [_path _name]
                                              {:success? true :base-branch "main"})]
        (let [result (controller/create-and-checkout-branch! ctrl "/tmp" "feat/x")]
          (is (:success? result))
          (is (= "main" (:base-branch result))))))))

(deftest create-and-checkout-branch-failure
  (testing "Returns DAG error on failure and adds history"
    (let [ctrl (make-controller)]
      (with-redefs [release/create-branch! (fn [_path _name]
                                              {:success? false :error "git error"})]
        (let [result (controller/create-and-checkout-branch! ctrl "/tmp" "feat/x")]
          (is (dag/err? result))
          (is (= :failed (:status @ctrl)))
          (is (some #(= :pr-creation-failed (:type %)) (:history @ctrl))))))))

;; apply-code-to-files!

(deftest apply-code-to-files-success
  (testing "Returns apply result on success"
    (let [ctrl (make-controller)]
      (with-redefs [fix/apply-fix-to-worktree (fn [_path _artifact _logger]
                                                 {:applied true})]
        (let [result (controller/apply-code-to-files! ctrl "/tmp" {:code/files []} nil)]
          (is (= {:applied true} result)))))))

(deftest apply-code-to-files-failure
  (testing "Returns DAG error when fix/apply returns error"
    (let [ctrl (make-controller)]
      (with-redefs [fix/apply-fix-to-worktree (fn [_path _artifact _logger]
                                                 (dag/err :apply-failed "bad artifact"))]
        (let [result (controller/apply-code-to-files! ctrl "/tmp" {:code/files []} nil)]
          (is (dag/err? result))
          (is (= :failed (:status @ctrl))))))))

;; commit-changes!

(deftest commit-changes-success
  (testing "Returns commit result on success"
    (let [ctrl (make-controller)
          task-id "task-00000001"]
      (with-redefs [release/commit-changes! (fn [_path _msg]
                                               {:success? true :commit-sha "abc123"})]
        (let [result (controller/commit-changes! ctrl "/tmp" test-task task-id)]
          (is (:success? result))
          (is (= "abc123" (:commit-sha result))))))))

(deftest commit-changes-failure
  (testing "Returns DAG error on commit failure"
    (let [ctrl (make-controller)]
      (with-redefs [release/commit-changes! (fn [_path _msg]
                                               {:success? false :error "nothing to commit"})]
        (let [result (controller/commit-changes! ctrl "/tmp" test-task "t-1")]
          (is (dag/err? result))
          (is (= :failed (:status @ctrl))))))))

(deftest commit-changes-uses-task-title-in-message
  (testing "Commit message includes task title"
    (let [ctrl (make-controller)
          captured-msg (atom nil)]
      (with-redefs [release/commit-changes! (fn [_path msg]
                                               (reset! captured-msg msg)
                                               {:success? true :commit-sha "x"})]
        (controller/commit-changes! ctrl "/tmp" test-task "t-1")
        (is (.contains @captured-msg "Implement feature X"))))))

;; push-and-create-pr!

(deftest push-and-create-pr-success
  (testing "Returns PR info map on success"
    (let [ctrl (make-controller)]
      (with-redefs [release/push-branch! (fn [_path _branch]
                                            {:success? true})
                    release/create-pr! (fn [_path _opts]
                                         {:success? true
                                          :pr-number 42
                                          :pr-url "https://github.com/org/repo/pull/42"})]
        (let [commit {:commit-sha "abc123"}
              result (controller/push-and-create-pr! ctrl "/tmp" "feat/x" "main"
                                                      test-task "t-1" commit)]
          (is (map? result))
          (is (not (dag/err? result)))
          (is (= 42 (:pr/id result)))
          (is (= "feat/x" (:pr/branch result)))
          (is (= "abc123" (:pr/head-sha result))))))))

(deftest push-and-create-pr-push-failure
  (testing "Returns DAG error when push fails"
    (let [ctrl (make-controller)]
      (with-redefs [release/push-branch! (fn [_path _branch]
                                            {:success? false :error "remote rejected"})]
        (let [result (controller/push-and-create-pr! ctrl "/tmp" "feat/x" "main"
                                                      test-task "t-1" {:commit-sha "x"})]
          (is (dag/err? result))
          (is (= :failed (:status @ctrl))))))))

(deftest push-and-create-pr-pr-creation-failure
  (testing "Returns DAG error when PR creation fails"
    (let [ctrl (make-controller)]
      (with-redefs [release/push-branch! (fn [_path _branch]
                                            {:success? true})
                    release/create-pr! (fn [_path _opts]
                                         {:success? false :error "repo not found"})]
        (let [result (controller/push-and-create-pr! ctrl "/tmp" "feat/x" "main"
                                                      test-task "t-1" {:commit-sha "x"})]
          (is (dag/err? result)))))))

(deftest push-and-create-pr-includes-acceptance-criteria
  (testing "PR body includes acceptance criteria from task"
    (let [ctrl (make-controller)
          captured-opts (atom nil)]
      (with-redefs [release/push-branch! (fn [_path _branch] {:success? true})
                    release/create-pr! (fn [_path opts]
                                         (reset! captured-opts opts)
                                         {:success? true :pr-number 1 :pr-url "url"})]
        (controller/push-and-create-pr! ctrl "/tmp" "feat/x" "main"
                                         test-task "t-1" {:commit-sha "x"})
        (is (.contains (:body @captured-opts) "Tests pass"))
        (is (.contains (:body @captured-opts) "No lint errors"))))))

;; finalize-pr-creation!

(deftest finalize-pr-creation-records-pr-and-returns-ok
  (testing "Stores PR on controller, adds history, returns dag/ok"
    (let [ctrl (make-controller)
          pr-info {:pr/id 42 :pr/url "url" :pr/branch "feat/x" :pr/head-sha "abc"}
          result (controller/finalize-pr-creation! ctrl pr-info "dag" "run" "task" nil nil)]
      (is (dag/ok? result))
      (is (= 42 (get-in @ctrl [:pr :pr/id])))
      (is (some #(= :pr-created (:type %)) (:history @ctrl))))))

(deftest finalize-pr-creation-publishes-event
  (testing "Publishes pr-opened event when event-bus is present"
    (let [ctrl (make-controller)
          published (atom [])
          mock-bus (reify Object)
          pr-info {:pr/id 42 :pr/url "url" :pr/branch "feat/x" :pr/head-sha "abc"}]
      (with-redefs [events/publish! (fn [_bus event _logger]
                                       (swap! published conj event))]
        (controller/finalize-pr-creation! ctrl pr-info "dag" "run" "task" mock-bus nil)
        (is (= 1 (count @published)))))))