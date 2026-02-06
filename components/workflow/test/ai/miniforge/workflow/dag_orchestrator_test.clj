(ns ai.miniforge.workflow.dag-orchestrator-test
  "Tests for DAG orchestration of multi-task plans."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn make-task
  "Create a task with given id, description, and dependencies."
  ([id] (make-task id (str "Task " id) []))
  ([id deps] (make-task id (str "Task " id) deps))
  ([id description deps]
   {:task/id id
    :task/description description
    :task/type :implement
    :task/dependencies deps}))

(defn make-plan
  "Create a plan with given tasks."
  [tasks]
  {:plan/id (random-uuid)
   :plan/name "test-plan"
   :plan/tasks tasks})

;------------------------------------------------------------------------------ Layer 1
;; parallelizable-plan? tests

(deftest test-parallelizable-plan-empty
  (testing "empty plan is not parallelizable"
    (is (not (dag-orch/parallelizable-plan? {:plan/tasks []})))))

(deftest test-parallelizable-plan-single-task
  (testing "single task plan is not parallelizable"
    (let [plan (make-plan [(make-task :a)])]
      (is (not (dag-orch/parallelizable-plan? plan))))))

(deftest test-parallelizable-plan-two-independent
  (testing "two independent tasks are parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b)])]
      (is (dag-orch/parallelizable-plan? plan)))))

(deftest test-parallelizable-plan-sequential-chain
  (testing "sequential chain is not parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])])]
      (is (not (dag-orch/parallelizable-plan? plan))))))

(deftest test-parallelizable-plan-diamond
  (testing "diamond pattern (A→B,C→D) is parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])
                           (make-task :c [:a])
                           (make-task :d [:b :c])])]
      (is (dag-orch/parallelizable-plan? plan)))))

(deftest test-parallelizable-plan-fan-out
  (testing "fan-out pattern (A→B,C,D) is parallelizable"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])
                           (make-task :c [:a])
                           (make-task :d [:a])])]
      (is (dag-orch/parallelizable-plan? plan)))))

;------------------------------------------------------------------------------ Layer 2
;; estimate-parallel-speedup tests

(deftest test-estimate-speedup-empty
  (testing "empty plan has no speedup"
    (let [result (dag-orch/estimate-parallel-speedup {:plan/tasks []})]
      (is (= 0 (:task-count result)))
      (is (= 0 (:max-parallel result)))
      (is (false? (:parallelizable? result))))))

(deftest test-estimate-speedup-single
  (testing "single task has no speedup"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :a)]))]
      (is (= 1 (:task-count result)))
      (is (= 1 (:max-parallel result)))
      (is (= 1 (:levels result)))
      (is (false? (:parallelizable? result)))
      (is (= 1.0 (:estimated-speedup result))))))

(deftest test-estimate-speedup-two-parallel
  (testing "two parallel tasks have 2x speedup"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :a)
                              (make-task :b)]))]
      (is (= 2 (:task-count result)))
      (is (= 2 (:max-parallel result)))
      (is (= 1 (:levels result)))
      (is (true? (:parallelizable? result)))
      (is (= 2.0 (:estimated-speedup result))))))

(deftest test-estimate-speedup-diamond
  (testing "diamond pattern has correct speedup"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :a)
                              (make-task :b [:a])
                              (make-task :c [:a])
                              (make-task :d [:b :c])]))]
      (is (= 4 (:task-count result)))
      (is (= 2 (:max-parallel result)))
      (is (= 3 (:levels result)))
      (is (true? (:parallelizable? result)))
      ;; 4 tasks / 3 levels ≈ 1.33
      (is (< 1.3 (:estimated-speedup result) 1.4)))))

(deftest test-estimate-speedup-wide-fan
  (testing "wide fan-out has high parallelism"
    (let [result (dag-orch/estimate-parallel-speedup
                  (make-plan [(make-task :root)
                              (make-task :a [:root])
                              (make-task :b [:root])
                              (make-task :c [:root])
                              (make-task :d [:root])]))]
      (is (= 5 (:task-count result)))
      (is (= 4 (:max-parallel result)))
      (is (= 2 (:levels result)))
      (is (true? (:parallelizable? result)))
      (is (= 2.5 (:estimated-speedup result))))))

;------------------------------------------------------------------------------ Layer 3
;; plan->dag-tasks tests

(deftest test-plan-to-dag-tasks-basic
  (testing "converts plan tasks to DAG format"
    (let [plan (make-plan [(make-task :a "Task A" [])
                           (make-task :b "Task B" [:a])])
          context {:workflow-id (random-uuid)}
          result (dag-orch/plan->dag-tasks plan context)]
      (is (= 2 (count result)))
      (is (= :a (:task/id (first result))))
      (is (= "Task A" (:task/description (first result))))
      (is (= #{} (:task/deps (first result))))
      (is (= :b (:task/id (second result))))
      (is (= #{:a} (:task/deps (second result)))))))

(deftest test-plan-to-dag-tasks-context
  (testing "includes context in task definitions"
    (let [plan-id (random-uuid)
          workflow-id (random-uuid)
          plan {:plan/id plan-id
                :plan/tasks [(make-task :a)]}
          context {:workflow-id workflow-id
                   :llm-backend :mock-llm
                   :artifact-store :mock-store}
          result (dag-orch/plan->dag-tasks plan context)
          task-context (:task/context (first result))]
      (is (= plan-id (:parent-plan-id task-context)))
      (is (= workflow-id (:parent-workflow-id task-context)))
      (is (= :mock-llm (:llm-backend task-context)))
      (is (= :mock-store (:artifact-store task-context))))))

(deftest test-plan-to-dag-tasks-defaults
  (testing "uses defaults for missing fields"
    (let [plan {:plan/tasks [{:task/id :minimal}]}
          result (dag-orch/plan->dag-tasks plan {})]
      (is (= :minimal (:task/id (first result))))
      (is (= :implement (:task/type (first result))))
      (is (= #{} (:task/deps (first result))))
      (is (= [] (:task/acceptance-criteria (first result)))))))

;------------------------------------------------------------------------------ Layer 4
;; execute-single-task tests

(deftest test-execute-single-task-placeholder
  (testing "returns placeholder result without LLM backend"
    (let [task-def {:task/id :test-task
                    :task/description "Test task"}
          result (dag-orch/execute-single-task task-def {})]
      (is (dag/ok? result))
      (let [data (dag/unwrap result)]
        (is (= :test-task (:task-id data)))
        (is (= "Test task" (:description data)))
        (is (= :implemented (:status data)))
        (is (= [] (:artifacts data)))
        (is (= {:tokens 0 :cost-usd 0.0} (:metrics data)))))))

(deftest test-execute-single-task-default-description
  (testing "uses default description when missing"
    (let [task-def {:task/id :no-desc}
          result (dag-orch/execute-single-task task-def {})]
      (is (dag/ok? result))
      (is (= "Implement task" (:description (dag/unwrap result)))))))

;------------------------------------------------------------------------------ Layer 5
;; create-task-executor-fn tests

(deftest test-create-executor-fn-callbacks
  (testing "executor fn invokes callbacks"
    (let [started (atom [])
          completed (atom [])
          context {}
          opts {:on-task-start #(swap! started conj %)
                :on-task-complete #(swap! completed conj [%1 %2])}
          executor (dag-orch/create-task-executor-fn context opts)
          dag-context {:run-state {:run/tasks {:task-1 {:task/id :task-1
                                                         :task/description "Test"}}}}
          result (executor :task-1 dag-context)]
      (is (= [:task-1] @started))
      (is (= 1 (count @completed)))
      (is (= :task-1 (first (first @completed))))
      (is (dag/ok? result)))))

(deftest test-create-executor-fn-no-callbacks
  (testing "executor fn works without callbacks"
    (let [executor (dag-orch/create-task-executor-fn {} {})
          dag-context {:run-state {:run/tasks {:task-1 {:task/id :task-1}}}}
          result (executor :task-1 dag-context)]
      (is (dag/ok? result)))))

;------------------------------------------------------------------------------ Layer 6
;; execute-plan-as-dag tests

(deftest test-execute-plan-as-dag-success
  (testing "executes parallel plan successfully"
    (let [[logger _] (log/collecting-logger)
          plan (make-plan [(make-task :a)
                           (make-task :b)])
          context {:logger logger}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      (is (= 2 (:tasks-completed result)))
      (is (= 0 (:tasks-failed result))))))

(deftest test-execute-plan-as-dag-with-deps
  (testing "executes plan with dependencies"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b)
                           (make-task :c [:a :b])])
          result (dag-orch/execute-plan-as-dag plan {})]
      (is (:success? result))
      (is (= 3 (:tasks-completed result))))))

(deftest test-execute-plan-as-dag-callbacks
  (testing "invokes task callbacks"
    (let [started (atom [])
          completed (atom [])
          plan (make-plan [(make-task :a)
                           (make-task :b)])
          context {:on-task-start #(swap! started conj %)
                   :on-task-complete (fn [id _] (swap! completed conj id))}
          result (dag-orch/execute-plan-as-dag plan context)]
      (is (:success? result))
      (is (= 2 (count @started)))
      (is (= 2 (count @completed)))
      (is (= (set @started) (set @completed))))))

;------------------------------------------------------------------------------ Layer 7
;; maybe-parallelize-plan tests

(deftest test-maybe-parallelize-sequential
  (testing "returns nil for sequential plan"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b [:a])])]
      (is (nil? (dag-orch/maybe-parallelize-plan plan {}))))))

(deftest test-maybe-parallelize-parallel
  (testing "executes parallelizable plan"
    (let [plan (make-plan [(make-task :a)
                           (make-task :b)])
          result (dag-orch/maybe-parallelize-plan plan {})]
      (is (some? result))
      (is (:success? result))
      (is (= 2 (:tasks-completed result))))))

(deftest test-maybe-parallelize-logs
  (testing "logs parallelization decision"
    (let [[logger entries] (log/collecting-logger)
          plan (make-plan [(make-task :a)
                           (make-task :b)
                           (make-task :c)])
          _ (dag-orch/maybe-parallelize-plan plan {:logger logger})]
      (is (some #(= :plan/parallelizing (:log/event %)) @entries)))))
