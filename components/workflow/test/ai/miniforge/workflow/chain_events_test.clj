(ns ai.miniforge.workflow.chain-events-test
  "Tests for chain lifecycle event emission."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.workflow.chain :as chain]
   [ai.miniforge.workflow.runner :as runner]
   [ai.miniforge.event-stream.core :as es-core]))

;------------------------------------------------------------------------------ Helpers

(defn- create-test-stream
  "Create a minimal event stream with no sinks for testing."
  []
  (es-core/create-event-stream {:sinks []}))

(defn- event-types
  "Extract ordered event types from a stream."
  [stream]
  (mapv :event/type (:events @stream)))

(defn- mock-load-workflow
  "Mock load-workflow that returns a stub workflow."
  [_wf-id _version _opts]
  {:workflow {:workflow/id :mock
              :workflow/pipeline [{:phase :plan}]}
   :source :mock})

(defn- mock-pipeline-success
  "Mock run-pipeline that always succeeds."
  [_workflow _input _opts]
  {:execution/status :completed
   :execution/output {:artifacts []
                      :phase-results {}
                      :last-phase-result {:plan "the-plan"}
                      :status :completed}})

(defn- mock-pipeline-failure
  "Mock run-pipeline that always fails."
  [_workflow _input _opts]
  {:execution/status :failed
   :execution/output {:artifacts []
                      :phase-results {}
                      :last-phase-result {:success? false}
                      :status :failed}
   :execution/error "LLM timeout"})

(defmacro with-chain-mocks
  "Run body with load-workflow and run-pipeline mocked.
   Uses alter-var-root to avoid corrupting requiring-resolve for other tests."
  [pipeline-fn & body]
  `(with-redefs [runner/run-pipeline ~pipeline-fn]
     ;; Temporarily install mock load-workflow via the emit-safe requiring-resolve
     ;; We avoid with-redefs on requiring-resolve to prevent cross-test pollution
     (let [saved-rr# @#'clojure.core/requiring-resolve]
       (try
         (alter-var-root #'clojure.core/requiring-resolve
                         (fn [orig#]
                           (fn [sym#]
                             (if (= sym# 'ai.miniforge.workflow.interface/load-workflow)
                               mock-load-workflow
                               (orig# sym#)))))
         ~@body
         (finally
           (alter-var-root #'clojure.core/requiring-resolve (constantly saved-rr#)))))))

;------------------------------------------------------------------------------ Layer 0
;; Success path event emission

(deftest chain-events-success-test
  (testing "successful 2-step chain emits correct event sequence"
    (let [stream (create-test-stream)
          chain-def {:chain/id :test-chain
                     :chain/version "1.0.0"
                     :chain/description "Test chain"
                     :chain/steps
                     [{:step/id :step-1
                       :step/workflow-id :workflow-a
                       :step/input-bindings {:task :task}}
                      {:step/id :step-2
                       :step/workflow-id :workflow-b
                       :step/input-bindings {:plan [:prev/last-phase-result :plan]}}]}
          opts {:event-stream stream}]
      (with-chain-mocks mock-pipeline-success
        (let [result (chain/run-chain chain-def {:task "build"} opts)]
          (is (= :completed (:chain/status result)))

          ;; Verify event sequence
          (is (= [:chain/started
                   :chain/step-started
                   :chain/step-completed
                   :chain/step-started
                   :chain/step-completed
                   :chain/completed]
                 (event-types stream)))

          ;; Verify chain-started event fields
          (let [started (first (:events @stream))]
            (is (= :test-chain (:chain/id started)))
            (is (= 2 (:chain/step-count started))))

          ;; Verify step-started has workflow-id
          (let [step-started (second (:events @stream))]
            (is (= :step-1 (:step/id step-started)))
            (is (= 0 (:step/index step-started)))
            (is (= :workflow-a (:step/workflow-id step-started))))

          ;; Verify chain-completed has duration and step count
          (let [completed (last (:events @stream))]
            (is (= :test-chain (:chain/id completed)))
            (is (= 2 (:chain/step-count completed)))
            (is (nat-int? (:chain/duration-ms completed)))))))))

;------------------------------------------------------------------------------ Layer 1
;; Failure path event emission

(deftest chain-events-failure-test
  (testing "failed step emits step-failed and chain-failed events"
    (let [stream (create-test-stream)
          chain-def {:chain/id :fail-chain
                     :chain/version "1.0.0"
                     :chain/description "Failing chain"
                     :chain/steps
                     [{:step/id :step-1
                       :step/workflow-id :workflow-a
                       :step/input-bindings {:task :task}}
                      {:step/id :step-2
                       :step/workflow-id :workflow-b
                       :step/input-bindings {:task :task}}]}
          opts {:event-stream stream}]
      (with-chain-mocks mock-pipeline-failure
        (let [result (chain/run-chain chain-def {:task "doomed"} opts)]
          (is (= :failed (:chain/status result)))

          ;; Verify event sequence: started, step-started, step-failed, chain-failed
          (is (= [:chain/started
                   :chain/step-started
                   :chain/step-failed
                   :chain/failed]
                 (event-types stream)))

          ;; Verify step-failed has error info
          (let [step-failed (nth (:events @stream) 2)]
            (is (= :step-1 (:step/id step-failed)))
            (is (= 0 (:step/index step-failed)))
            (is (= "LLM timeout" (:chain/error step-failed))))

          ;; Verify chain-failed has failed step reference
          (let [chain-failed (last (:events @stream))]
            (is (= :step-1 (:chain/failed-step chain-failed)))
            (is (= "LLM timeout" (:chain/error chain-failed)))))))))

;------------------------------------------------------------------------------ Layer 2
;; No event-stream — silent execution

(deftest chain-no-event-stream-test
  (testing "chain runs silently when no event-stream in opts"
    (with-chain-mocks mock-pipeline-success
      (let [result (chain/run-chain
                     {:chain/id :quiet-chain
                      :chain/steps [{:step/id :s1
                                     :step/workflow-id :wf-a
                                     :step/input-bindings {}}]}
                     {}
                     {})]
        (is (= :completed (:chain/status result)))))))
