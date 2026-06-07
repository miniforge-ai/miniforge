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

(ns ai.miniforge.task-executor.interface
  "Polylith public API for task-executor component.

  Re-exports core functions via def aliases per codebase convention."
  (:require [ai.miniforge.task-executor.bridge :as bridge]
            [ai.miniforge.task-executor.generate :as generate]
            [ai.miniforge.task-executor.runner :as runner]
            [ai.miniforge.task-executor.orchestrator :as orchestrator]))

;; Bridge - event translation
(def pr-event->scheduler-action
  "Map of PR lifecycle event-type keywords to DAG scheduler action keywords.

  Conflict and rebase events deliberately map to :ci-failed to reuse the
  existing retry logic. Event types absent from this map have no scheduler
  mapping and are handled internally by the PR controller."
  bridge/pr-event->scheduler-action)

(def translate-event
  "Translate a PR lifecycle event to a scheduler event.

  Args: a PR event map with :event/type and :task-id (plus optional :timestamp
  and arbitrary extra keys).

  Returns a scheduler event map {:event/action :event/task-id :timestamp
  :metadata}, or nil when :event/type has no mapping in
  pr-event->scheduler-action."
  bridge/translate-event)

(def create-scheduler-event
  "Build a scheduler event map from explicit components.

  Args: task-id (string), action (scheduler action keyword), and an optional
  opts map (:timestamp, :metadata).

  Returns an event map {:event/action :event/task-id :timestamp} suitable for
  dag scheduler/handle-task-event, including :metadata only when supplied."
  bridge/create-scheduler-event)

(def unmapped-event?
  "Return true when the given PR event-type keyword has no scheduler mapping,
  false otherwise."
  bridge/unmapped-event?)

;; Generate - factory for generate-fn closures
(def format-task-prompt
  "Format a task and its execution context into an agent prompt.

  Args: task (map with :description, optional :files, :dependencies,
  :acceptance-criteria) and context (map with :worktree-path, :base-commit).

  Returns a string prompt for the LLM."
  generate/format-task-prompt)

(def create-generate-fn
  "Create a generate-fn closure wrapping the inner loop and implementer agent.

  Args: llm-backend (agent backend instance) and keyword opts (:logger,
  :event-stream, :workflow-id, :max-iterations — default 10 iterations).

  Returns a function (fn [task context] -> {:artifact map :tokens int}),
  reusable for initial code generation and PR fix loops."
  generate/create-generate-fn)

;; Runner - single task execution
(def create-task-context
  "Assemble the per-task context map for a single task execution.

  Args: task-id (string), task (task definition map), run-context (shared run
  context with :executor, :config, :logger, :event-stream, :run-atom).

  Returns a context map carrying the task, workflow-id, executor, logger,
  event-stream, run-atom, and config/budget fields needed by execute-task."
  runner/create-task-context)

(def execute-task
  "Execute one task through its full lifecycle: validate spec, acquire
  resources, generate code, run the blocking PR lifecycle, record metrics,
  and clean up resources in a finally block.

  Args: task-id (string), task (definition map), run-context (shared context).

  Returns {:ok? true :value lifecycle-result} on success, or
  {:ok? false :error throwable :error-classification map} on failure. Never
  throws — all exceptions are caught and the task is marked failed in the DAG."
  runner/execute-task)

;; Orchestrator - concurrent execution and scheduler integration
(def create-run-context
  "Create the shared context for a whole DAG run.

  Args: run-atom (atom holding DAG run state) and config (map with
  :workflow-id, :executor, :llm-backend, :logger, :event-stream,
  :max-iterations, :max-parallel, :github-token, :budget, optional :lock-pool).

  Returns a run-context map; a lock-pool is created (sized to :max-parallel,
  default 4) when none is supplied in config."
  orchestrator/create-run-context)

(def make-execute-task-fn
  "Create the execute-task-fn callback the DAG scheduler invokes per task.

  Args: run-context (from create-run-context).

  Returns a function (fn [task-id scheduler-context] -> future). The returned
  fn looks up the task, runs runner/execute-task in a future (tracked for
  shutdown), cascades failures to dependents, and returns nil when the
  task-id is unknown."
  orchestrator/make-execute-task-fn)

(def create-orchestrated-scheduler-context
  "Build a scheduler context with execute-task-fn already wired in.

  Convenience over create-run-context + make-execute-task-fn.

  Args: run-atom (atom holding DAG run state) and config (see
  create-run-context).

  Returns a scheduler context map {:run-atom :execute-task-fn :max-parallel
  :config} (:max-parallel defaults to 4)."
  orchestrator/create-orchestrated-scheduler-context)

(def execute-dag!
  "Execute a DAG end-to-end: build run state, wire the orchestrated scheduler,
  poll the scheduler loop (1s interval) until a terminal status, and return.

  Args: dag-id (string), task-defs (seq of maps with :task/id and :task/deps),
  config (see create-run-context).

  Returns the final dereferenced run-atom state map (terminal :status is one of
  :completed, :failed, :budget-exceeded). Re-throws on scheduler-loop error."
  orchestrator/execute-dag!)

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
