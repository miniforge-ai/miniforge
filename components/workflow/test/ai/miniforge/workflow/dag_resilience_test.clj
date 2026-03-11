(ns ai.miniforge.workflow.dag-resilience-test
  "Tests for DAG rate limit detection, batch analysis, and failover handling."
  (:require
   [clojure.test :refer [deftest testing is are]]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [ai.miniforge.workflow.dag-resilience :as resilience]
   [ai.miniforge.workflow.dag-orchestrator :as dag-orch]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn ok-result [task-id]
  (dag/ok {:task-id task-id :status :implemented}))

(defn rate-limit-err [message]
  (dag/err :task-execution-failed message {:task-id :test}))

(defn generic-err [message]
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

(deftest test-rate-limit-error-handles-map-message
  (testing "handles non-string :message (error map) without throwing ClassCastException"
    (let [result (dag/err :task-execution-failed
                          {:type :phase-error :phase :implement
                           :message "Sub-workflow failed"
                           :data {:some "context"}}
                          {:task-id :test})]
      (is (not (resilience/rate-limit-error? result))))))

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
      (is (str/includes? (:reason decision) "disabled")))))

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
          _original-fn @(resolve 'ai.miniforge.workflow.dag-orchestrator/execute-single-task)]
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

;------------------------------------------------------------------------------ Layer 5
;; Regression: phantom dependency detection and unreached task reporting
;;
;; Bug: Planner generates dependency UUIDs referencing non-existent tasks.
;; Tasks with phantom deps never become ready (dep not in completed-ids)
;; and never fail (nothing actually failed). The loop exits on
;; (empty? ready-tasks) and silently drops them — reporting success
;; with 0 failures despite tasks never running.

(deftest test-plan->dag-tasks-drops-phantom-deps
  (testing "dependencies referencing non-existent task IDs are dropped"
    (let [task-a (random-uuid)
          task-b (random-uuid)
          phantom (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies [task-a phantom]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})]
      ;; Task B should only have task-a as a dep; phantom is dropped
      (is (= #{task-a} (:task/deps (second dag-tasks))))
      ;; Task A has no deps
      (is (empty? (:task/deps (first dag-tasks)))))))

(deftest test-plan->dag-tasks-drops-string-phantom-deps
  (testing "string UUID dependencies to non-existent tasks are also dropped"
    (let [task-a (random-uuid)
          task-b (random-uuid)
          phantom-str (str (random-uuid))
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies [(str task-a) phantom-str]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})]
      ;; task-a string should resolve; phantom string should be dropped
      (is (= #{task-a} (:task/deps (second dag-tasks)))))))

(deftest test-plan->dag-tasks-all-deps-valid
  (testing "valid dependencies are preserved unchanged"
    (let [task-a (random-uuid)
          task-b (random-uuid)
          task-c (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id task-a
                              :task/description "A" :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "B" :task/type :implement
                              :task/dependencies []}
                             {:task/id task-c
                              :task/description "C" :task/type :implement
                              :task/dependencies [task-a task-b]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          task-c-dag (first (filter #(= task-c (:task/id %)) dag-tasks))]
      (is (= #{task-a task-b} (:task/deps task-c-dag))))))

(deftest test-plan->dag-tasks-keyword-task-ids
  (testing "keyword task IDs are preserved and dependencies resolve against them"
    (let [plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id :task-a
                              :task/description "A" :task/type :implement
                              :task/dependencies []}
                             {:task/id :task-b
                              :task/description "B" :task/type :implement
                              :task/dependencies [:task-a]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          id-a (:task/id (first dag-tasks))
          id-b (:task/id (second dag-tasks))]
      (is (= :task-a id-a))
      (is (= :task-b id-b))
      ;; Dependency resolved correctly
      (is (= #{id-a} (:task/deps (second dag-tasks)))))))

(deftest test-plan->dag-tasks-keyword-deps-resolve
  (testing "keyword dependencies resolve to the correct normalized task IDs"
    (let [plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id :alpha
                              :task/description "Alpha" :task/type :implement
                              :task/dependencies []}
                             {:task/id :beta
                              :task/description "Beta" :task/type :implement
                              :task/dependencies []}
                             {:task/id :gamma
                              :task/description "Gamma" :task/type :implement
                              :task/dependencies [:alpha :beta]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          ids (into {} (map (juxt :task/description :task/id) dag-tasks))
          gamma-task (first (filter #(= "Gamma" (:task/description %)) dag-tasks))]
      (is (= #{(ids "Alpha") (ids "Beta")} (:task/deps gamma-task))))))

(deftest test-plan->dag-tasks-mixed-id-types
  (testing "plan with mixed UUID and keyword IDs preserves both consistently"
    (let [uuid-id (random-uuid)
          plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id uuid-id
                              :task/description "UUID task" :task/type :implement
                              :task/dependencies []}
                             {:task/id :keyword-task
                              :task/description "Keyword task" :task/type :implement
                              :task/dependencies [uuid-id]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})]
      ;; UUID task ID preserved as-is
      (is (= uuid-id (:task/id (first dag-tasks))))
      ;; Keyword task ID preserved as-is
      (is (= :keyword-task (:task/id (second dag-tasks))))
      ;; Dependency on UUID task resolves
      (is (= #{uuid-id} (:task/deps (second dag-tasks)))))))

(deftest test-plan->dag-tasks-string-non-uuid-ids
  (testing "non-UUID string task IDs are preserved and dependencies resolve against them"
    (let [plan {:plan/id (random-uuid)
                :plan/tasks [{:task/id "build-fixtures"
                              :task/description "Build" :task/type :implement
                              :task/dependencies []}
                             {:task/id "run-tests"
                              :task/description "Test" :task/type :test
                              :task/dependencies ["build-fixtures"]}]}
          dag-tasks (dag-orch/plan->dag-tasks plan {})
          id-build (:task/id (first dag-tasks))
          id-test (:task/id (second dag-tasks))]
      (is (= "build-fixtures" id-build))
      (is (= "run-tests" id-test))
      (is (= #{id-build} (:task/deps (second dag-tasks)))))))

(deftest test-phantom-deps-caused-stuck-tasks-before-fix
  (testing "regression: phantom deps no longer cause silently stuck tasks"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          task-c (random-uuid)
          phantom (random-uuid)
          ;; task-c depends on task-a (valid) and phantom (non-existent)
          ;; Before fix: task-c would never run and never be reported as failed
          plan {:plan/id (random-uuid)
                :plan/name "phantom-dep-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-c
                              :task/description "Task C"
                              :task/type :implement
                              :task/dependencies [task-a phantom]}]}
          executed-tasks (atom #{})]
      (with-redefs [dag-orch/execute-single-task
                    (fn [task-def _context]
                      (swap! executed-tasks conj (:task/id task-def))
                      (dag/ok {:task-id (:task/id task-def)
                               :status :implemented
                               :artifacts []
                               :metrics {:tokens 0 :cost-usd 0.0}}))]
        (let [result (dag-orch/execute-plan-as-dag plan {:logger logger})]
          ;; All 3 tasks should complete — phantom dep dropped at plan conversion
          (is (:success? result))
          (is (= 3 (:tasks-completed result)))
          (is (= 0 (:tasks-failed result)))
          (is (= #{task-a task-b task-c} @executed-tasks)))))))

(deftest test-unreached-tasks-reported-in-result
  (testing "tasks stuck due to failed deps are reported as unreached"
    (let [[logger _] (log/collecting-logger)
          task-a (random-uuid)
          task-b (random-uuid)
          task-c (random-uuid)
          ;; task-c depends on task-b, which will fail
          plan {:plan/id (random-uuid)
                :plan/name "unreached-plan"
                :plan/tasks [{:task/id task-a
                              :task/description "Task A"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-b
                              :task/description "Task B"
                              :task/type :implement
                              :task/dependencies []}
                             {:task/id task-c
                              :task/description "Task C — depends on B"
                              :task/type :implement
                              :task/dependencies [task-b]}]}]
      (with-redefs [dag-orch/execute-single-task
                    (fn [task-def _context]
                      (if (= (:task/id task-def) task-b)
                        (dag/err :task-execution-failed
                                 "Compilation error" {})
                        (dag/ok {:task-id (:task/id task-def)
                                 :status :implemented
                                 :artifacts []
                                 :metrics {:tokens 0 :cost-usd 0.0}})))]
        (let [result (dag-orch/execute-plan-as-dag plan {:logger logger})]
          ;; task-a completed, task-b failed, task-c transitively failed
          (is (not (:success? result)))
          (is (= 1 (:tasks-completed result)))
          ;; task-b failed directly + task-c transitively = 2 failed
          (is (= 2 (:tasks-failed result)))
          ;; No tasks unreached — all accounted for via propagation
          (is (= 0 (or (:tasks-unreached result) 0))))))))

(deftest test-all-tasks-accounted-for
  (testing "completed + failed + unreached = total (no silent data loss)"
    (let [[logger _] (log/collecting-logger)
          task-ids (repeatedly 6 random-uuid)
          [a b c d e f] task-ids
          ;; a, b: independent. c depends on a. d depends on b.
          ;; e depends on c and d. f: independent.
          ;; b will fail -> d transitively fails -> e transitively fails
          plan {:plan/id (random-uuid)
                :plan/name "accounting-plan"
                :plan/tasks [{:task/id a :task/description "A"
                              :task/type :implement :task/dependencies []}
                             {:task/id b :task/description "B"
                              :task/type :implement :task/dependencies []}
                             {:task/id c :task/description "C"
                              :task/type :implement :task/dependencies [a]}
                             {:task/id d :task/description "D"
                              :task/type :implement :task/dependencies [b]}
                             {:task/id e :task/description "E"
                              :task/type :implement :task/dependencies [c d]}
                             {:task/id f :task/description "F"
                              :task/type :implement :task/dependencies []}]}]
      (with-redefs [dag-orch/execute-single-task
                    (fn [task-def _context]
                      (if (= (:task/id task-def) b)
                        (dag/err :task-execution-failed "fail" {})
                        (dag/ok {:task-id (:task/id task-def)
                                 :status :implemented :artifacts []
                                 :metrics {:tokens 0 :cost-usd 0.0}})))]
        (let [result (dag-orch/execute-plan-as-dag plan {:logger logger})
              total 6
              accounted (+ (:tasks-completed result)
                           (:tasks-failed result)
                           (or (:tasks-unreached result) 0))]
          ;; a, c, f complete. b fails. d, e transitively fail.
          (is (= 3 (:tasks-completed result)))
          (is (= 3 (:tasks-failed result)))
          (is (= total accounted)
              "Every task must be accounted for — no silent drops"))))))

(deftest test-sub-workflow-strips-release-phase
  (testing "sub-workflow pipeline excludes :explore, :plan, and :release phases"
    (let [task-def {:task/id (random-uuid)
                    :task/description "Test task"}
          context {:execution/workflow
                   {:workflow/pipeline
                    [{:phase :explore}
                     {:phase :plan}
                     {:phase :implement}
                     {:phase :verify}
                     {:phase :review}
                     {:phase :release}
                     {:phase :done}]}}
          sub-wf (dag-orch/task-sub-workflow task-def context)
          phase-names (mapv :phase (:workflow/pipeline sub-wf))]
      (is (= [:implement :verify :review :done] phase-names)
          "Sub-workflow should strip :explore, :plan, and :release")))

  (testing "sub-workflow with no parent pipeline falls back to minimal"
    (let [task-def {:task/id (random-uuid)
                    :task/description "Test task"}
          context {:execution/workflow {:workflow/pipeline []}}
          sub-wf (dag-orch/task-sub-workflow task-def context)
          phase-names (mapv :phase (:workflow/pipeline sub-wf))]
      (is (= [:implement :done] phase-names)))))

;------------------------------------------------------------------------------ Layer 6
;; rate-limit-in-text? tests

(deftest test-rate-limit-in-text-detects-patterns
  (testing "detects rate limit patterns in arbitrary text"
    (are [text] (resilience/rate-limit-in-text? text)
      "You've hit your limit · resets 2pm"
      "Claude CLI rate limited: You've hit your limit · resets 2pm (America/Los_Angeles)"
      "API rate limit exceeded"
      "429 Too Many Requests"
      "Quota exceeded for this billing period"
      "resets 3pm (US/Pacific)")))

(deftest test-rate-limit-in-text-rejects-normal-text
  (testing "does not flag normal text"
    (are [text] (not (resilience/rate-limit-in-text? text))
      "Syntax error in foo.clj"
      "Connection refused"
      nil
      "")))

;------------------------------------------------------------------------------ Layer 7
;; parse-reset-instant tests

(deftest test-parse-reset-instant-absolute-time
  (testing "parses 'resets 2pm' as an instant"
    (let [text "You've hit your limit · resets 2pm"
          result (resilience/parse-reset-instant text)]
      (is (instance? Instant result))
      (is (.isAfter result (Instant/now)))))

  (testing "parses 'resets 2pm (America/Los_Angeles)' with timezone"
    (let [text "You've hit your limit · resets 2pm (America/Los_Angeles)"
          result (resilience/parse-reset-instant text)]
      (is (instance? Instant result))))

  (testing "parses 'resets 2:30pm' with minutes"
    (let [text "resets 2:30pm"
          result (resilience/parse-reset-instant text)]
      (is (instance? Instant result)))))

(deftest test-parse-reset-instant-relative-time
  (testing "parses 'resets in 30 minutes' as relative duration"
    (let [before (Instant/now)
          result (resilience/parse-reset-instant "resets in 30 minutes")
          after (Instant/now)]
      (is (instance? Instant result))
      ;; Should be approximately 30 minutes from now
      (is (> (.toEpochMilli result) (+ (.toEpochMilli before) 1790000)))
      (is (< (.toEpochMilli result) (+ (.toEpochMilli after) 1810000))))))

(deftest test-parse-reset-instant-no-match
  (testing "returns nil for non-reset text"
    (is (nil? (resilience/parse-reset-instant "Syntax error")))
    (is (nil? (resilience/parse-reset-instant nil)))))

;------------------------------------------------------------------------------ Layer 8
;; handle-rate-limited-batch with reset time waiting

(deftest test-handle-rate-limited-batch-waits-for-reset
  (testing "waits for reset when reset time is imminent (relative)"
    (let [[logger _] (log/collecting-logger)
          rate-limit-msg "You've hit your limit · resets in 1 seconds"
          results {:b (dag/err :task-execution-failed rate-limit-msg {:task-id :b})}
          decision (resilience/handle-rate-limited-batch
                    {} #{:b} #{:a} logger results)]
      ;; Should have waited and returned :continue
      (is (= :continue (:action decision)))
      (is (number? (:waited-ms decision))))))

(deftest test-handle-rate-limited-batch-pauses-without-results
  (testing "pauses when no results provided (backward compat)"
    (let [[logger _] (log/collecting-logger)
          decision (resilience/handle-rate-limited-batch
                    {} #{:b} #{:a} logger)]
      (is (= :pause (:action decision))))))

;------------------------------------------------------------------------------ Layer 9
;; extract-sub-workflow-error tests

(deftest test-extract-sub-workflow-error-from-phase-results
  (testing "extracts error from phase results when execution errors empty"
    (let [result {:execution/errors []
                  :execution/phase-results
                  {:implement {:error {:message "Claude CLI rate limited: You've hit your limit"}}}}]
      (is (= "Claude CLI rate limited: You've hit your limit"
             (dag-orch/extract-sub-workflow-error result)))))

  (testing "prefers execution errors when available"
    (let [result {:execution/errors [{:message "Exceeded 5 redirects"}]
                  :execution/phase-results
                  {:implement {:error {:message "some phase error"}}}}]
      (is (= "Exceeded 5 redirects"
             (dag-orch/extract-sub-workflow-error result)))))

  (testing "falls back to default message"
    (is (= "Sub-workflow failed"
           (dag-orch/extract-sub-workflow-error {})))))

;------------------------------------------------------------------------------ Resume helpers

(defn temp-dir []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-resilience-test-" (System/nanoTime)))
    .mkdirs))

(defn cleanup! [dir]
  (doseq [f (.listFiles dir)]
    (when (.isDirectory f) (cleanup! f))
    (.delete f))
  (.delete dir))

(defn write-events! [dir filename events]
  (let [f (io/file dir filename)]
    (with-open [w (io/writer f)]
      (doseq [event events]
        (.write w (pr-str event))
        (.write w "\n")))
    f))

;------------------------------------------------------------------------------ Layer 3: safe-read-edn

(deftest test-safe-read-edn-normal-values
  (testing "reads map"
    (is (= {:a 1 :b "hello"} (resilience/safe-read-edn "{:a 1 :b \"hello\"}"))))
  (testing "reads vector"
    (is (= [1 2 3] (resilience/safe-read-edn "[1 2 3]"))))
  (testing "reads keyword"
    (is (= :foo (resilience/safe-read-edn ":foo")))))

(deftest test-safe-read-edn-tagged-literals
  (testing "reads #uuid"
    (let [result (resilience/safe-read-edn
                  "{:id #uuid \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\"}")]
      (is (uuid? (:id result)))))
  (testing "reads #inst"
    (let [result (resilience/safe-read-edn
                  "{:ts #inst \"2026-03-08T18:14:28Z\"}")]
      (is (some? (:ts result)))))
  (testing "tolerates #object tags"
    (let [result (resilience/safe-read-edn
                  (str "{:err #object[java.time.Instant 0x25d0 "
                       "\"2026-03-08T18:14:28Z\"]}"))]
      (is (some? result)))))

(deftest test-safe-read-edn-error-cases
  (testing "returns nil for malformed EDN"
    (is (nil? (resilience/safe-read-edn "{:broken {{{ invalid"))))
  (testing "returns nil for empty string"
    (is (nil? (resilience/safe-read-edn ""))))
  (testing "returns nil for nil input"
    (is (nil? (resilience/safe-read-edn nil)))))

;------------------------------------------------------------------------------ Layer 3: read-event-file

(deftest test-read-event-file-happy-path
  (testing "reads all events from file"
    (let [dir (temp-dir)]
      (try
        (let [events [{:event/type :workflow/started :workflow/id "test"}
                      {:event/type :dag/task-completed :dag/task-id :task-a}
                      {:event/type :workflow/failed}]
              f (write-events! dir "test.edn" events)
              result (resilience/read-event-file (.getAbsolutePath f))]
          (is (= 3 (count result)))
          (is (= :workflow/started (:event/type (first result))))
          (is (= :workflow/failed (:event/type (last result)))))
        (finally (cleanup! dir))))))

(deftest test-read-event-file-skips-blank-lines
  (testing "blank lines between events are ignored"
    (let [dir (temp-dir)]
      (try
        (let [f (io/file dir "gaps.edn")]
          (spit f (str (pr-str {:event/type :a}) "\n"
                       "\n"
                       "   \n"
                       (pr-str {:event/type :b}) "\n"))
          (let [result (resilience/read-event-file (.getAbsolutePath f))]
            (is (= 2 (count result)))))
        (finally (cleanup! dir))))))

(deftest test-read-event-file-missing-file
  (testing "returns nil for non-existent file"
    (is (nil? (resilience/read-event-file "/nonexistent/path.edn")))))

;------------------------------------------------------------------------------ Layer 3: Extract functions

(deftest test-extract-completed-task-ids
  (testing "extracts only :dag/task-completed event task IDs"
    (let [events [{:event/type :workflow/started}
                  {:event/type :dag/task-completed :dag/task-id :task-a}
                  {:event/type :dag/task-failed :dag/task-id :task-b}
                  {:event/type :dag/task-completed :dag/task-id :task-c}
                  {:event/type :workflow/failed}]]
      (is (= #{:task-a :task-c} (resilience/extract-completed-task-ids events)))))
  (testing "returns empty set when no completions"
    (is (= #{} (resilience/extract-completed-task-ids
                [{:event/type :workflow/started}]))))
  (testing "deduplicates"
    (let [events [{:event/type :dag/task-completed :dag/task-id :task-a}
                  {:event/type :dag/task-completed :dag/task-id :task-a}]]
      (is (= #{:task-a} (resilience/extract-completed-task-ids events))))))

(deftest test-extract-completed-artifacts
  (testing "collects artifacts from completed tasks"
    (let [events [{:event/type :dag/task-completed
                   :dag/task-id :task-a
                   :dag/result {:data {:artifacts [{:code/id "art-1"}]}}}
                  {:event/type :dag/task-completed
                   :dag/task-id :task-b
                   :dag/result {:data {:artifacts [{:code/id "art-2"} {:code/id "art-3"}]}}}]]
      (is (= 3 (count (resilience/extract-completed-artifacts events))))))
  (testing "handles tasks with no artifacts"
    (let [events [{:event/type :dag/task-completed
                   :dag/result {:data {}}}]]
      (is (= [] (resilience/extract-completed-artifacts events)))))
  (testing "ignores non-completed events"
    (let [events [{:event/type :dag/task-failed
                   :dag/result {:data {:artifacts [{:code/id "ignored"}]}}}]]
      (is (= [] (resilience/extract-completed-artifacts events))))))

;------------------------------------------------------------------------------ Layer 3: Resume context end-to-end

(deftest test-resume-context-end-to-end
  (testing "builds full resume context from event file"
    (let [dir (temp-dir)]
      (try
        (let [events [{:event/type :workflow/started :workflow/id "wf-123"}
                      {:event/type :dag/task-completed
                       :dag/task-id :task-a
                       :dag/result {:data {:artifacts [{:code/id "art-1"}]}}}
                      {:event/type :dag/task-failed
                       :dag/task-id :task-b
                       :dag/error {:message "quota"}}
                      {:event/type :dag/task-completed
                       :dag/task-id :task-c
                       :dag/result {:data {:artifacts [{:code/id "art-2"}]}}}]
              f (write-events! dir "wf-123.edn" events)
              parsed (resilience/read-event-file (.getAbsolutePath f))
              completed (resilience/extract-completed-task-ids parsed)
              artifacts (resilience/extract-completed-artifacts parsed)]
          (is (= #{:task-a :task-c} completed))
          (is (not (contains? completed :task-b)))
          (is (= 2 (count artifacts))))
        (finally (cleanup! dir)))))
  (testing "resume-context-from-event-file returns non-resumed for missing file"
    (let [ctx (resilience/resume-context-from-event-file "nonexistent-workflow-id")]
      (is (= #{} (:pre-completed-ids ctx)))
      (is (= [] (:pre-completed-artifacts ctx)))
      (is (false? (:resumed? ctx))))))
