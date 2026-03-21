(ns ai.miniforge.task-executor.interface
  "Polylith public API for task-executor component.

  Re-exports core functions via def aliases per codebase convention."
  (:require [ai.miniforge.task-executor.bridge :as bridge]
            [ai.miniforge.task-executor.generate :as generate]
            [ai.miniforge.task-executor.runner :as runner]
            [ai.miniforge.task-executor.orchestrator :as orchestrator]))

;; Bridge - event translation
(def pr-event->scheduler-action bridge/pr-event->scheduler-action)
(def translate-event bridge/translate-event)
(def create-scheduler-event bridge/create-scheduler-event)
(def unmapped-event? bridge/unmapped-event?)

;; Generate - factory for generate-fn closures
(def format-task-prompt generate/format-task-prompt)
(def create-generate-fn generate/create-generate-fn)

;; Runner - single task execution
(def create-task-context runner/create-task-context)
(def execute-task runner/execute-task)

;; Orchestrator - concurrent execution and scheduler integration
(def create-run-context orchestrator/create-run-context)
(def make-execute-task-fn orchestrator/make-execute-task-fn)
(def create-orchestrated-scheduler-context orchestrator/create-orchestrated-scheduler-context)
(def execute-dag! orchestrator/execute-dag!)

;; Rich comment block for REPL testing
(comment
  ;; Example: Create orchestrated scheduler context
  (def run-atom (atom {:status :pending
                       :tasks {"task-1" {:description "Test task"
                                        :dependencies []}}}))

  (def config {:workflow-id "test-wf"
               :executor nil ; mock
               :llm-backend nil ; mock
               :logger nil
               :max-parallel 2})

  (def scheduler-context
    (create-orchestrated-scheduler-context run-atom config))

  ;; Execute-task-fn is now available in scheduler-context
  (:execute-task-fn scheduler-context)

  ;; Example: Execute a full DAG
  (def result
    (execute-dag! "test-dag"
                  {"task-1" {:description "Implement feature X"
                            :dependencies []
                            :files ["src/core.clj"]}
                   "task-2" {:description "Test feature X"
                            :dependencies ["task-1"]
                            :files ["test/core_test.clj"]}}
                  config))

  ;; Check final state
  (:status result)
  (:task-states result)

  ;; Example: Event translation
  (translate-event {:event/type :pr/ci-passed
                    :task-id "task-123"
                    :timestamp (java.util.Date.)})

  ;; => {:event/action :ci-passed
  ;;     :event/task-id "task-123"
  ;;     :timestamp ...}
  )
