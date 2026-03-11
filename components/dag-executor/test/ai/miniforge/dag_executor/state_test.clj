(ns ai.miniforge.dag-executor.state-test
  "Tests for DAG task state management and event emission wiring."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.dag-executor.state-profile :as profiles]
   [ai.miniforge.dag-executor.state :as state]
   [ai.miniforge.dag-executor.result :as result]
   [ai.miniforge.event-stream.interface :as es]))

;; ============================================================================
;; State transition tests
;; ============================================================================

(deftest transition-task-pure-test
  (testing "valid transition returns ok result"
    (let [task (state/create-task-state (random-uuid) #{})
          result (state/transition-task task :ready)]
      (is (result/ok? result))
      (is (= :ready (:task/status (:data result))))))

  (testing "invalid transition returns error"
    (let [task (state/create-task-state (random-uuid) #{})
          result (state/transition-task task :merged)]
      (is (result/err? result)))))

(deftest transition-task!-test
  (testing "atomically transitions task in run state"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (state/create-run-state (random-uuid) {task-id task})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (is (= :ready (get-in @run-atom [:run/tasks task-id :task/status])))))

  (testing "returns error for nonexistent task"
    (let [run (state/create-run-state (random-uuid) {})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom (random-uuid) :ready nil)]
      (is (result/err? result)))))

;; ============================================================================
;; Event emission wiring tests
;; ============================================================================

(deftest transition-task!-emits-event-test
  (testing "transition-task! emits task/state-changed when event-stream is configured"
    (let [stream (es/create-event-stream {:sinks []})
          wf-id (random-uuid)
          dag-id (random-uuid)
          task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (-> (state/create-run-state dag-id {task-id task})
                  (assoc-in [:run/config :event-stream] stream)
                  (assoc-in [:run/config :workflow-id] wf-id))
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (let [events (es/get-events stream)]
        (is (= 1 (count events)))
        (let [event (first events)]
          (is (= :task/state-changed (:event/type event)))
          (is (= dag-id (:dag/id event)))
          (is (= task-id (:task/id event)))
          (is (= :pending (:task/from-state event)))
          (is (= :ready (:task/to-state event))))))))

(deftest transition-task!-works-without-event-stream-test
  (testing "transition-task! works normally without event-stream in config"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{})
          run (state/create-run-state (random-uuid) {task-id task})
          run-atom (state/create-run-atom run)
          result (state/transition-task! run-atom task-id :ready nil)]
      (is (result/ok? result))
      (is (= :ready (get-in @run-atom [:run/tasks task-id :task/status]))))))

;; ============================================================================
;; Query tests
;; ============================================================================

(deftest ready-tasks-test
  (testing "finds tasks with all deps satisfied"
    (let [task-a-id (random-uuid)
          task-b-id (random-uuid)
          task-a (state/create-task-state task-a-id #{} :state-profile :kernel)
          task-b (state/create-task-state task-b-id #{task-a-id} :state-profile :kernel)
          run (state/create-run-state (random-uuid)
                                      {task-a-id task-a
                                       task-b-id task-b}
                                      :state-profile :kernel)]
      ;; Only task-a is ready (no deps)
      (is (= #{task-a-id} (state/ready-tasks run)))
      ;; After marking task-a completed, task-b becomes ready
      (let [run' (-> run
                     (assoc-in [:run/tasks task-a-id :task/status] :completed)
                     (state/mark-task-completed task-a-id))]
        (is (contains? (state/ready-tasks run') task-b-id))))))

(deftest blocked-tasks-test
  (testing "blocked tasks use profile success states instead of merge-only bookkeeping"
    (let [task-a-id (random-uuid)
          task-b-id (random-uuid)
          task-a (state/create-task-state task-a-id #{} :state-profile :kernel)
          task-b (state/create-task-state task-b-id #{task-a-id} :state-profile :kernel)
          run (state/create-run-state (random-uuid)
                                      {task-a-id task-a
                                       task-b-id task-b}
                                      :state-profile :kernel)
          run' (-> run
                   (assoc-in [:run/tasks task-a-id :task/status] :completed)
                   (state/mark-task-completed task-a-id))]
      (is (= {task-b-id #{task-a-id}}
             (state/blocked-tasks run)))
      (is (= {}
             (state/blocked-tasks run'))))))

(deftest running-tasks-test
  (testing "running tasks respect the task profile rather than the kernel default"
    (let [provider (profiles/build-provider
                    {:default-profile :software-factory
                     :profiles
                     {:software-factory
                      {:profile/id :software-factory
                       :task-statuses [:pending :ready :implementing :merged :failed :skipped]
                       :terminal-statuses [:merged :failed :skipped]
                       :success-terminal-statuses [:merged]
                       :valid-transitions {:pending [:ready]
                                           :ready [:implementing]
                                           :implementing [:merged :failed]
                                           :merged []
                                           :failed []
                                           :skipped []}
                       :event-mappings {}}}})
          task-id (random-uuid)
          task (state/create-task-state task-id #{}
                                        :state-profile :software-factory
                                        :state-profile-provider provider)
          run (-> (state/create-run-state (random-uuid) {task-id task}
                                          :state-profile :software-factory
                                          :state-profile-provider provider)
                  (assoc-in [:run/tasks task-id :task/status] :merged))]
      (is (= #{} (state/running-tasks run))))))

(deftest terminal?-test
  (testing "terminal statuses"
    (is (state/terminal? :completed))
    (is (state/terminal? :failed))
    (is (state/terminal? :skipped))
    (is (not (state/terminal? :pending)))
    (is (not (state/terminal? :running))))

  (testing "terminal? respects explicit non-kernel profiles"
    (let [software-factory-provider
          (profiles/build-provider
           {:default-profile :software-factory
            :profiles
            {:software-factory
             {:profile/id :software-factory
              :task-statuses [:pending :ready :implementing :merged :failed :skipped]
              :terminal-statuses [:merged :failed :skipped]
              :success-terminal-statuses [:merged]
              :valid-transitions {:pending [:ready]
                                  :ready [:implementing]
                                  :implementing [:merged :failed]
                                  :merged []
                                  :failed []
                                  :skipped []}
              :event-mappings {}}}})]
      (is (state/terminal? (profiles/resolve-profile software-factory-provider :software-factory)
                           :merged)))))

(deftest resource-backed-profile-registry-test
  (testing "kernel profile registry is loaded from kernel resources"
    (is (= :kernel (profiles/default-profile-id)))
    (is (= [:kernel] (vec (profiles/available-profile-ids))))))

(deftest explicit-provider-resolution-test
  (testing "explicit providers resolve project-specific profiles without classpath overrides"
    (let [provider (profiles/build-provider
                    {:default-profile :software-factory
                     :profiles
                     {:software-factory
                      {:profile/id :software-factory
                       :task-statuses [:pending :ready :implementing :merged :failed :skipped]
                       :terminal-statuses [:merged :failed :skipped]
                       :success-terminal-statuses [:merged]
                       :valid-transitions {:pending [:ready]
                                           :ready [:implementing]
                                           :implementing [:merged :failed]
                                           :merged []
                                           :failed []
                                           :skipped []}
                       :event-mappings {:merged {:type :complete :to :merged}}}
                      :etl
                      {:profile/id :etl
                       :task-statuses [:pending :ready :running :completed :failed :skipped]
                       :terminal-statuses [:completed :failed :skipped]
                       :success-terminal-statuses [:completed]
                       :valid-transitions {:pending [:ready]
                                           :ready [:running]
                                           :running [:completed :failed]
                                           :completed []
                                           :failed []
                                           :skipped []}
                       :event-mappings {:completed {:type :complete :to :completed}}}}})]
      (is (= #{:software-factory :etl}
             (set (profiles/available-profile-ids provider))))
      (is (= :software-factory (profiles/default-profile-id provider)))
      (is (= :merged
             (-> (profiles/resolve-profile provider :software-factory)
                 :success-terminal-statuses
                 first))))))

(deftest profile-resolution-falls-back-to-kernel-test
  (testing "unknown profiles fall back to the configured default profile"
    (let [kernel-definition {:profile/id :kernel
                             :task-statuses [:pending :ready :running :completed :failed :skipped]
                             :terminal-statuses [:completed :failed :skipped]
                             :success-terminal-statuses [:completed]
                             :valid-transitions {:pending [:ready :skipped]
                                                 :ready [:running :skipped]
                                                 :running [:completed :failed]
                                                 :completed []
                                                 :failed []
                                                 :skipped []}
                             :event-mappings {:completed {:type :complete :to :completed}}}
          provider (profiles/build-provider
                    {:default-profile :kernel
                     :profiles {:kernel kernel-definition}})]
      (is (= :kernel (profiles/default-profile-id provider)))
      (is (= (profiles/build-profile kernel-definition)
             (profiles/resolve-profile provider :missing-profile))))))

(deftest build-profile-normalizes-transition-targets-test
  (testing "resource-shaped profile data is normalized into set-based transitions"
    (let [profile (profiles/build-profile
                   {:profile/id :vector-backed
                    :task-statuses [:pending :ready :completed]
                    :terminal-statuses [:completed]
                    :success-terminal-statuses [:completed]
                    :valid-transitions {:pending [:ready]
                                        :ready [:completed]
                                        :completed []}
                    :event-mappings {}})]
      (is (= #{:ready} (get-in profile [:valid-transitions :pending])))
      (is (state/valid-transition? profile :pending :ready))
      (is (not (state/valid-transition? profile :pending :completed))))))

(deftest kernel-profile-completion-test
  (testing "generic kernel profile reaches :completed without merge semantics"
    (let [task-id (random-uuid)
          task (state/create-task-state task-id #{} :state-profile :kernel)
          run (state/create-run-state (random-uuid) {task-id task} :state-profile :kernel)
          run-atom (state/create-run-atom run)]
      (is (= (profiles/resolve-profile :kernel) (get-in @run-atom [:run/config :state-profile])))
      (is (result/ok? (state/transition-task! run-atom task-id :ready nil)))
      (is (result/ok? (state/transition-task! run-atom task-id :running nil)))
      (is (result/ok? (state/mark-completed! run-atom task-id nil)))
      (is (= :completed (get-in @run-atom [:run/tasks task-id :task/status])))
      (is (contains? (:run/completed @run-atom) task-id))
      (is (= :completed (state/compute-run-status @run-atom))))))

(deftest explicit-provider-construction-test
  (testing "run construction uses the supplied provider for keyword profiles"
    (let [provider (profiles/build-provider
                    {:default-profile :software-factory
                     :profiles
                     {:software-factory
                      {:profile/id :software-factory
                       :task-statuses [:pending :ready :implementing :merged :failed :skipped]
                       :terminal-statuses [:merged :failed :skipped]
                       :success-terminal-statuses [:merged]
                       :valid-transitions {:pending [:ready]
                                           :ready [:implementing]
                                           :implementing [:merged :failed]
                                           :merged []
                                           :failed []
                                           :skipped []}
                       :event-mappings {:merged {:type :complete :to :merged}}}}})
          task-id (random-uuid)
          task (state/create-task-state task-id #{}
                                        :state-profile :software-factory
                                        :state-profile-provider provider)
          run (state/create-run-state (random-uuid) {task-id task}
                                      :state-profile :software-factory
                                      :state-profile-provider provider)
          run-atom (state/create-run-atom run)]
      (is (= :software-factory (get-in task [:task/state-profile :profile/id])))
      (is (= :software-factory (get-in run [:run/config :state-profile :profile/id])))
      (is (result/ok? (state/transition-task! run-atom task-id :ready nil)))
      (is (result/ok? (state/transition-task! run-atom task-id :implementing nil)))
      (is (result/ok? (state/mark-merged! run-atom task-id nil)))
      (is (= :merged (get-in @run-atom [:run/tasks task-id :task/status])))
      (is (= :completed (state/compute-run-status @run-atom))))))
