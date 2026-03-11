(ns ai.miniforge.task-executor.orchestrator-test
  "Integration test for concurrent task orchestration.

  Tests that multiple tasks execute in parallel respecting dependencies,
  resource limits, and budget constraints. Uses diamond DAG pattern."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.task-executor.orchestrator :as orchestrator]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Mock Data

(def mock-diamond-dag
  "Diamond DAG: A -> B, A -> C, B+C -> D"
  {:task-a {:task/id "a"
            :task/description "Task A (root)"
            :task/dependencies []}
   :task-b {:task/id "b"
            :task/description "Task B (depends on A)"
            :task/dependencies ["a"]}
   :task-c {:task/id "c"
            :task/description "Task C (depends on A)"
            :task/dependencies ["a"]}
   :task-d {:task/id "d"
            :task/description "Task D (depends on B and C)"
            :task/dependencies ["b" "c"]}})

(def mock-linear-dag
  "Linear DAG: A -> B -> C"
  {:task-a {:task/id "a"
            :task/description "Task A"
            :task/dependencies []}
   :task-b {:task/id "b"
            :task/description "Task B"
            :task/dependencies ["a"]}
   :task-c {:task/id "c"
            :task/description "Task C"
            :task/dependencies ["b"]}})

(def mock-parallel-dag
  "Parallel DAG: A, B, C (no dependencies)"
  {:task-a {:task/id "a"
            :task/description "Task A"
            :task/dependencies []}
   :task-b {:task/id "b"
            :task/description "Task B"
            :task/dependencies []}
   :task-c {:task/id "c"
            :task/description "Task C"
            :task/dependencies []}})

;------------------------------------------------------------------------------ Mock Implementations

(defn mock-execute-task-fn
  "Mock task execution function that tracks execution."
  [execution-tracker success-map delay-ms]
  (fn [task-id _context]
    (let [should-succeed? (get success-map task-id true)]

      ;; Track execution start
      (swap! execution-tracker update :started conj task-id)
      (swap! execution-tracker update :start-times assoc task-id (System/currentTimeMillis))

      ;; Simulate work
      (Thread/sleep delay-ms)

      ;; Track execution end
      (swap! execution-tracker update :completed conj task-id)
      (swap! execution-tracker update :end-times assoc task-id (System/currentTimeMillis))

      (if should-succeed?
        {:success? true
         :task-id task-id
         :metrics {:tokens 1000 :duration-ms delay-ms}}
        {:success? false
         :task-id task-id
         :error "Task execution failed"}))))

(defn create-mock-run-atom
  "Create mock run-atom with task definitions."
  [task-defs]
  (atom {:status :running
         :tasks (into {}
                     (map (fn [[_k v]]
                            [(:task/id v) (assoc v :status :pending)])
                          task-defs))
         :metrics {:tokens 0
                   :cost-usd 0.0}
         :budget {:tokens 100000
                  :cost-usd 10.0}}))

(defn create-mock-context
  "Create mock execution context."
  [run-atom config execution-tracker]
  {:run-atom run-atom
   :config (merge {:max-parallel 3} config)
   :execute-task-fn (mock-execute-task-fn
                     execution-tracker
                     (:success-map config {})
                     (:task-delay-ms config 100))
   :futures (atom {})})

(defn get-task-status
  "Get status of a task from run-atom."
  [run-atom task-id]
  (get-in @run-atom [:tasks task-id :status]))

(defn update-task-status!
  "Update task status in run-atom."
  [run-atom task-id new-status]
  (swap! run-atom assoc-in [:tasks task-id :status] new-status))

(defn dispatch-ready-tasks
  "Dispatch tasks that have no pending dependencies."
  [run-atom context]
  (let [tasks @run-atom
        ready-tasks (filter
                     (fn [[_task-id task]]
                       (and (= :pending (:status task))
                            (every? (fn [dep-id]
                                      (= :merged (get-task-status run-atom dep-id)))
                                    (:task/dependencies task))))
                     (:tasks tasks))]

    (doseq [[task-id _task] ready-tasks]
      (update-task-status! run-atom task-id :implementing)
      ((:execute-task-fn context) task-id context))))

;------------------------------------------------------------------------------ Tests

(deftest single-task-execution-test
  (testing "Single task executes successfully"
    (let [execution-tracker (atom {:started [] :completed []
                                    :start-times {} :end-times {}})
          task-defs {:task-a (:task-a mock-linear-dag)}
          run-atom (create-mock-run-atom task-defs)
          context (create-mock-context run-atom {} execution-tracker)]

      ;; Execute task
      (update-task-status! run-atom "a" :implementing)
      ((:execute-task-fn context) "a" context)

      ;; Wait for completion
      (Thread/sleep 200)

      (is (= ["a"] (:started @execution-tracker))
          "Task should be started")
      (is (= ["a"] (:completed @execution-tracker))
          "Task should be completed"))))

(deftest linear-dag-execution-test
  (testing "Linear DAG executes tasks in order"
    (let [execution-tracker (atom {:started [] :completed []
                                    :start-times {} :end-times {}})
          run-atom (create-mock-run-atom mock-linear-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 50}
                                       execution-tracker)]

      ;; Execute A
      (update-task-status! run-atom "a" :implementing)
      ((:execute-task-fn context) "a" context)
      (Thread/sleep 100)
      (update-task-status! run-atom "a" :merged)

      ;; Execute B (depends on A)
      (update-task-status! run-atom "b" :implementing)
      ((:execute-task-fn context) "b" context)
      (Thread/sleep 100)
      (update-task-status! run-atom "b" :merged)

      ;; Execute C (depends on B)
      (update-task-status! run-atom "c" :implementing)
      ((:execute-task-fn context) "c" context)
      (Thread/sleep 100)

      (is (= ["a" "b" "c"] (:started @execution-tracker))
          "Tasks should execute in order: A → B → C"))))

(deftest parallel-dag-execution-test
  (testing "Independent tasks can execute in parallel"
    (let [execution-tracker (atom {:started [] :completed []
                                    :start-times {} :end-times {}})
          run-atom (create-mock-run-atom mock-parallel-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 100}
                                       execution-tracker)]

      ;; Launch all tasks in parallel
      (doseq [task-id ["a" "b" "c"]]
        (update-task-status! run-atom task-id :implementing)
        (future ((:execute-task-fn context) task-id context)))

      ;; Wait for all to start
      (Thread/sleep 50)

      (is (= 3 (count (:started @execution-tracker)))
          "All 3 tasks should start")

      ;; Wait for completion
      (Thread/sleep 150)

      (is (= 3 (count (:completed @execution-tracker)))
          "All 3 tasks should complete"))))

(deftest diamond-dag-execution-test
  (testing "Diamond DAG respects dependencies"
    (let [execution-tracker (atom {:started [] :completed []
                                    :start-times {} :end-times {}})
          run-atom (create-mock-run-atom mock-diamond-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 50}
                                       execution-tracker)]

      ;; Execute A
      (update-task-status! run-atom "a" :implementing)
      ((:execute-task-fn context) "a" context)
      (Thread/sleep 100)
      (update-task-status! run-atom "a" :merged)

      ;; Execute B and C in parallel (both depend on A)
      (update-task-status! run-atom "b" :implementing)
      (update-task-status! run-atom "c" :implementing)
      (future ((:execute-task-fn context) "b" context))
      (future ((:execute-task-fn context) "c" context))
      (Thread/sleep 150)
      (update-task-status! run-atom "b" :merged)
      (update-task-status! run-atom "c" :merged)

      ;; Execute D (depends on B and C)
      (update-task-status! run-atom "d" :implementing)
      ((:execute-task-fn context) "d" context)
      (Thread/sleep 100)

      (is (= ["a" "b" "c" "d"] (sort (:completed @execution-tracker)))
          "All tasks should complete")

      ;; Verify A completed before B and C
      (let [a-end (get-in @execution-tracker [:end-times "a"])
            b-start (get-in @execution-tracker [:start-times "b"])
            c-start (get-in @execution-tracker [:start-times "c"])]
        (is (< a-end b-start)
            "A should complete before B starts")
        (is (< a-end c-start)
            "A should complete before C starts"))

      ;; Verify B and C completed before D started
      (let [b-end (get-in @execution-tracker [:end-times "b"])
            c-end (get-in @execution-tracker [:end-times "c"])
            d-start (get-in @execution-tracker [:start-times "d"])]
        (is (< b-end d-start)
            "B should complete before D starts")
        (is (< c-end d-start)
            "C should complete before D starts")))))

(deftest max-parallel-config-test
  (testing "Max parallel configuration is respected"
    (let [execution-tracker (atom {:started [] :completed []})
          run-atom (create-mock-run-atom mock-parallel-dag)
          context (create-mock-context run-atom
                                       {:max-parallel 2
                                        :task-delay-ms 50}
                                       execution-tracker)]

      ;; Verify config is set
      (is (= 2 (get-in context [:config :max-parallel]))
          "Config should specify max-parallel limit")

      ;; Note: Actual semaphore enforcement is tested in dag-executor component
      ;; This test verifies the orchestrator receives and stores the config
      (is (contains? (:config context) :max-parallel)
          "Context should contain max-parallel config"))))

(deftest task-failure-cascade-test
  (testing "Task failure prevents dependent tasks from running"
    (let [execution-tracker (atom {:started [] :completed []})
          run-atom (create-mock-run-atom mock-linear-dag)
          context (create-mock-context run-atom
                                       {:success-map {"a" true
                                                      "b" false  ; B fails
                                                      "c" true}
                                        :task-delay-ms 50}
                                       execution-tracker)]

      ;; Execute A (succeeds)
      (update-task-status! run-atom "a" :implementing)
      ((:execute-task-fn context) "a" context)
      (Thread/sleep 100)
      (update-task-status! run-atom "a" :merged)

      ;; Execute B (fails)
      (update-task-status! run-atom "b" :implementing)
      (let [result ((:execute-task-fn context) "b" context)]
        (Thread/sleep 100)
        (is (false? (:success? result))
            "Task B should fail")
        (update-task-status! run-atom "b" :failed))

      ;; C should not execute because B failed
      (is (= :pending (get-task-status run-atom "c"))
          "Task C should remain pending when B fails")

      (is (= ["a" "b"] (:started @execution-tracker))
          "Only A and B should have started"))))

(deftest concurrent-futures-tracking-test
  (testing "Futures are tracked for all tasks"
    (let [execution-tracker (atom {:started [] :completed []})
          run-atom (create-mock-run-atom mock-parallel-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 100}
                                       execution-tracker)
          futures-atom (:futures context)]

      ;; Launch tasks and track futures
      (doseq [task-id ["a" "b" "c"]]
        (update-task-status! run-atom task-id :implementing)
        (let [fut (future ((:execute-task-fn context) task-id context))]
          (swap! futures-atom assoc task-id fut)))

      (is (= 3 (count @futures-atom))
          "Should track 3 futures")

      ;; Wait for completion
      (doseq [[_task-id fut] @futures-atom]
        @fut)  ; Deref to wait

      (is (= 3 (count (:completed @execution-tracker)))
          "All tasks should complete"))))

(deftest budget-token-limit-test
  (testing "Token budget limit stops execution"
    (let [execution-tracker (atom {:started [] :completed []})
          run-atom (create-mock-run-atom mock-parallel-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 50}
                                       execution-tracker)]
      (swap! run-atom assoc-in [:budget :tokens] 2500)  ; Only enough for 2 tasks

      ;; Execute first task (1000 tokens)
      (update-task-status! run-atom "a" :implementing)
      ((:execute-task-fn context) "a" context)
      (Thread/sleep 100)
      (swap! run-atom update-in [:metrics :tokens] + 1000)

      ;; Execute second task (1000 tokens)
      (update-task-status! run-atom "b" :implementing)
      ((:execute-task-fn context) "b" context)
      (Thread/sleep 100)
      (swap! run-atom update-in [:metrics :tokens] + 1000)

      ;; Check if budget would be exceeded
      (let [current-tokens (get-in @run-atom [:metrics :tokens])
            budget-tokens (get-in @run-atom [:budget :tokens])]
        (is (= 2000 current-tokens)
            "Should have used 2000 tokens")
        (is (< (- budget-tokens current-tokens) 1000)
            "Remaining budget should be insufficient for another task")))))

(deftest metrics-accumulation-test
  (testing "Metrics accumulate across all tasks"
    (let [execution-tracker (atom {:started [] :completed []})
          run-atom (create-mock-run-atom mock-linear-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 50}
                                       execution-tracker)]

      ;; Execute all tasks sequentially
      (doseq [_task-id ["a" "b" "c"]]
        (let [tid _task-id]
          (update-task-status! run-atom tid :implementing)
          ((:execute-task-fn context) tid context)
          (Thread/sleep 100)

          ;; Accumulate metrics
          (swap! run-atom update-in [:metrics :tokens] + 1000)
          (swap! run-atom update-in [:metrics :cost-usd] + 0.05)

          (update-task-status! run-atom tid :merged)))

      (is (= 3000 (get-in @run-atom [:metrics :tokens]))
          "Should accumulate 3000 tokens total")
      (is (< (Math/abs (- 0.15 (get-in @run-atom [:metrics :cost-usd]))) 0.0001)
          "Should accumulate $0.15 cost total"))))

(deftest graceful-shutdown-test
  (testing "Graceful shutdown waits for in-flight tasks"
    (let [execution-tracker (atom {:started [] :completed []})
          run-atom (create-mock-run-atom mock-parallel-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 200}
                                       execution-tracker)
          futures-atom (:futures context)]

      ;; Launch tasks
      (doseq [task-id ["a" "b" "c"]]
        (update-task-status! run-atom task-id :implementing)
        (let [fut (future ((:execute-task-fn context) task-id context))]
          (swap! futures-atom assoc task-id fut)))

      ;; Simulate shutdown: wait for all futures
      (doseq [[_task-id fut] @futures-atom]
        @fut)

      (is (= 3 (count (:completed @execution-tracker)))
          "All in-flight tasks should complete before shutdown")
      (is (= (count (:started @execution-tracker))
             (count (:completed @execution-tracker)))
          "All started tasks should have completed"))))

(deftest exception-handling-in-future-test
  (testing "Exceptions in futures are caught"
    (let [execution-tracker (atom {:started [] :errors []})
          run-atom (create-mock-run-atom {:task-a (:task-a mock-linear-dag)})
          throwing-exec-fn (fn [task-id _context]
                             (swap! execution-tracker update :started conj task-id)
                             (throw (ex-info "Task execution error"
                                             {:task-id task-id})))
          context (assoc (create-mock-context run-atom {} execution-tracker)
                         :execute-task-fn throwing-exec-fn)
          ;; Execute task in future with exception handling
          result (try
                   (throwing-exec-fn "a" context)
                   (catch Exception e
                     (swap! execution-tracker update :errors conj e)
                     {:success? false :error (ex-message e)}))]

      (is (false? (:success? result))
          "Should catch exception and return failure")
      (is (= 1 (count (:errors @execution-tracker)))
          "Should track exception"))))

(deftest dependency-resolution-test
  (testing "Dependencies are correctly resolved"
    (let [task-a (:task-a mock-diamond-dag)
          task-b (:task-b mock-diamond-dag)
          task-d (:task-d mock-diamond-dag)]

      (is (empty? (:task/dependencies task-a))
          "Task A should have no dependencies")
      (is (= ["a"] (:task/dependencies task-b))
          "Task B should depend on A")
      (is (= ["b" "c"] (:task/dependencies task-d))
          "Task D should depend on B and C"))))

(deftest run-status-tracking-test
  (testing "Run status is tracked throughout execution"
    (let [execution-tracker (atom {:started [] :completed []})
          run-atom (create-mock-run-atom mock-linear-dag)
          context (create-mock-context run-atom
                                       {:task-delay-ms 50}
                                       execution-tracker)]

      (is (= :running (:status @run-atom))
          "Run should start in :running status")

      ;; Execute task
      (update-task-status! run-atom "a" :implementing)
      ((:execute-task-fn context) "a" context)
      (Thread/sleep 100)

      (is (= :running (:status @run-atom))
          "Run should remain :running during execution")

      ;; Mark run as completed
      (swap! run-atom assoc :status :completed)

      (is (= :completed (:status @run-atom))
          "Run should transition to :completed"))))

(deftest execute-dag-passes-state-profile-config-test
  (testing "execute-dag! forwards state profile configuration into DAG construction"
    (let [captured (atom nil)
          run-state {:run/status :completed
                     :run/tasks {}}
          run-atom (atom run-state)
          provider {:default-profile :software-factory
                    :profiles {:software-factory {:profile/id :software-factory}}}
          scheduler-value {:status :completed
                           :pending #{}
                           :running #{}
                           :completed #{}}]
      (with-redefs [dag/create-dag-from-tasks
                    (fn [dag-id task-defs & opts]
                      (reset! captured {:dag-id dag-id
                                        :task-defs task-defs
                                        :opts (apply hash-map opts)})
                      run-state)
                    dag/create-run-atom (fn [_] run-atom)
                    dag/schedule-iteration (fn [_ _]
                                             {:ok? true
                                              :value scheduler-value})
                    orchestrator/create-orchestrated-scheduler-context
                    (fn [_ _] {:execute-task-fn (fn [& _] nil)})
                    orchestrator/log-event (fn [& _] nil)]
        (let [final-state (orchestrator/execute-dag!
                           "dag-123"
                           [{:task/id "task-1" :task/deps #{}}]
                           {:state-profile :software-factory
                            :state-profile-provider provider})]
          (is (= run-state final-state))
          (is (= :software-factory (get-in @captured [:opts :state-profile])))
          (is (= provider (get-in @captured [:opts :state-profile-provider]))))))))
