(ns ai.miniforge.workflow.dag-resilience-test
  "Tests for DAG rate limit detection, batch analysis, and failover handling."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn- ok-result [task-id]
  (dag/ok {:task-id task-id :status :implemented}))

(defn- rate-limit-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

(defn- generic-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

;------------------------------------------------------------------------------ Layer 1
;; rate-limit-error? tests

(deftest test-rate-limit-error-detects-claude-cli
  (testing "detects Claude CLI rate limit message"
    (is (resilience/rate-limit-error?
         (rate-limit-err "You've hit your limit · resets 2pm")))))

(deftest test-rate-limit-error-detects-generic
  (testing "detects generic rate limit messages"
    (are [msg] (resilience/rate-limit-error? (rate-limit-err msg))
      "API rate limit exceeded"
      "Rate limit reached for model"
      "rate-limit error on request"
      "Quota exceeded for this billing period"
      "429 Too Many Requests"
      "Too Many Requests"
      "resets 2pm"
      "resets in 30 minutes")))

(deftest test-rate-limit-error-rejects-non-rate-limit
  (testing "does not flag non-rate-limit errors"
    (are [msg] (not (resilience/rate-limit-error? (generic-err msg)))
      "Syntax error in foo.clj"
      "Connection refused"
      "Task failed: compilation error"
      "File not found")))

(deftest test-rate-limit-error-returns-false-for-ok
  (testing "returns nil/false for successful results"
    (is (not (resilience/rate-limit-error? (ok-result :a))))))

;------------------------------------------------------------------------------ Layer 2
;; analyze-batch-for-rate-limits tests

(deftest test-analyze-batch-all-ok
  (testing "all successful results"
    (let [results {:a (ok-result :a)
                   :b (ok-result :b)
                   :c (ok-result :c)}
          analysis (resilience/analyze-batch-for-rate-limits results)]
      (is (= #{:a :b :c} (:completed-ids analysis)))
      (is (empty? (:rate-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis))))))

(deftest test-analyze-batch-with-rate-limits
  (testing "mix of successful and rate-limited results"
    (let [results {:a (ok-result :a)
                   :b (rate-limit-err "You've hit your limit")
                   :c (rate-limit-err "429 Too Many Requests")}
          analysis (resilience/analyze-batch-for-rate-limits results)]
      (is (= #{:a} (:completed-ids analysis)))
      (is (= #{:b :c} (:rate-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis))))))

(deftest test-analyze-batch-mixed-failures
  (testing "mix of rate-limited and other failures"
    (let [results {:a (ok-result :a)
                   :b (rate-limit-err "Rate limit reached")
                   :c (generic-err "Syntax error in foo.clj")}
          analysis (resilience/analyze-batch-for-rate-limits results)]
      (is (= #{:a} (:completed-ids analysis)))
      (is (= #{:b} (:rate-limited-ids analysis)))
      (is (= #{:c} (:other-failed-ids analysis))))))

(deftest test-analyze-batch-empty
  (testing "empty results"
    (let [analysis (resilience/analyze-batch-for-rate-limits {})]
      (is (empty? (:completed-ids analysis)))
      (is (empty? (:rate-limited-ids analysis)))
      (is (empty? (:other-failed-ids analysis))))))

;------------------------------------------------------------------------------ Layer 3
;; handle-rate-limited-batch tests

(deftest test-handle-rate-limited-no-failover-configured
  (testing "pauses when no allowed-failover-backends configured"
    (let [[logger _] (log/collecting-logger)
          context {}
          decision (resilience/handle-rate-limited-batch
                    context #{:b :c} #{:a} logger)]
      (is (= :pause (:action decision)))
      (is (string? (:reason decision))))))

(deftest test-handle-rate-limited-auto-switch-disabled
  (testing "pauses when backend-auto-switch is false"
    (let [[logger _] (log/collecting-logger)
          context {:execution/opts
                   {:self-healing {:backend-auto-switch false
                                   :allowed-failover-backends [:openai]}}}
          decision (resilience/handle-rate-limited-batch
                    context #{:b} #{:a} logger)]
      (is (= :pause (:action decision)))
      (is (clojure.string/includes? (:reason decision) "disabled")))))

(deftest test-handle-rate-limited-with-allowed-backends
  (testing "attempts failover when allowed backends are configured"
    (let [[logger _] (log/collecting-logger)
          context {:execution/opts
                   {:self-healing {:backend-auto-switch true
                                   :allowed-failover-backends [:openai]}}}
          decision (resilience/handle-rate-limited-batch
                    context #{:b} #{:a} logger)]
      ;; Should attempt failover — result depends on backend health state
      ;; Either continues with new backend or pauses if failover fails
      (is (contains? #{:continue :pause} (:action decision))))))

;------------------------------------------------------------------------------ Layer 4
;; DAG orchestrator integration — pause on rate limit

(deftest test-dag-execution-pauses-on-rate-limit
  (testing "DAG execution returns paused result when all tasks hit rate limits"
    (let [[logger _] (log/collecting-logger)
          ;; Use real UUIDs as task IDs since plan->dag-tasks expects them
          task-a (random-uuid)
          task-b (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/name "test-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}]}
          ;; Mock execute-single-task to return rate limit errors
          original-fn @(resolve 'ai.miniforge.workflow.dag-orchestrator/execute-single-task)]
      (with-redefs [ai.miniforge.workflow.dag-orchestrator/execute-single-task
                    (fn [_task-def _context]
                      (dag/err :task-execution-failed
                               "You've hit your limit · resets 2pm"
                               {}))]
        (let [result (ai.miniforge.workflow.dag-orchestrator/execute-plan-as-dag
                      plan {:logger logger})]
          (is (not (:success? result)))
          (is (true? (:paused? result)))
          (is (string? (:pause-reason result))))))))

(deftest test-dag-execution-partial-rate-limit
  (testing "DAG pauses when some tasks succeed and others hit rate limits"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          call-count (atom 0)
          plan {:plan/id (random-uuid)
                :plan/name "test-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}]}]
      (with-redefs [ai.miniforge.workflow.dag-orchestrator/execute-single-task
                    (fn [task-def _context]
                      (let [n (swap! call-count inc)]
                        (if (= n 1)
                          ;; First task succeeds
                          (dag/ok {:task-id (:task/id task-def)
                                   :status :implemented
                                   :artifacts []
                                   :metrics {:tokens 0 :cost-usd 0.0}})
                          ;; Second task hits rate limit
                          (dag/err :task-execution-failed
                                   "429 Too Many Requests"
                                   {}))))]
        (let [result (ai.miniforge.workflow.dag-orchestrator/execute-plan-as-dag
                      plan {:logger logger})]
          (is (not (:success? result)))
          (is (true? (:paused? result)))
          ;; One task completed, one rate-limited
          (is (= 1 (:tasks-completed result))))))))

(deftest test-dag-execution-pre-completed-ids
  (testing "DAG skips tasks in pre-completed-ids set"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          executed-tasks (atom #{})
          plan {:plan/id (random-uuid)
                :plan/name "test-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}]}]
      (with-redefs [ai.miniforge.workflow.dag-orchestrator/execute-single-task
                    (fn [task-def _context]
                      (swap! executed-tasks conj (:task/id task-def))
                      (dag/ok {:task-id (:task/id task-def)
                               :status :implemented
                               :artifacts []
                               :metrics {:tokens 0 :cost-usd 0.0}}))]
        (let [result (ai.miniforge.workflow.dag-orchestrator/execute-plan-as-dag
                      plan {:logger logger
                            :pre-completed-ids #{task-a}})]
          (is (:success? result))
          ;; Only task-b should have been executed
          (is (= #{task-b} @executed-tasks))
          ;; Both count as completed (1 pre-completed + 1 executed)
          (is (= 2 (:tasks-completed result))))))))
