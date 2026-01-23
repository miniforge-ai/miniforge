;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.workflow.agent-factory
  "Factory for creating agents, gates, and tasks from workflow configuration.
   Maps workflow phase configuration to actual agent, gate, and task instances."
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.task.interface :as task]
   [ai.miniforge.artifact.interface :as artifact]))

;------------------------------------------------------------------------------ Layer 0
;; Agent mapping

(defn create-agent-for-phase
  "Create agent instance from :phase/agent keyword.
   Returns agent instance or nil for :none.

   Agent Mapping:
   - :planner -> agent/create-planner
   - :implementer -> agent/create-implementer
   - :tester -> agent/create-tester
   - :spec-analyzer -> planner with spec-focus prompt
   - :designer -> planner with design-focus prompt
   - :reviewer -> stub (run lint gates, return approval)
   - :releaser -> stub (marker phase)
   - :observer -> stub (collect metrics, no LLM)
   - :none -> nil (skip phase)

   Arguments:
   - phase: Phase configuration map with :phase/agent
   - context: Execution context with :llm-backend

   Returns agent instance or nil."
  [phase context]
  (let [agent-type (:phase/agent phase)
        llm-backend (:llm-backend context)]

    (case agent-type
      ;; Core agents
      :planner (agent/create-planner {:llm-backend llm-backend})
      :implementer (agent/create-implementer {:llm-backend llm-backend})
      :tester (agent/create-tester {:llm-backend llm-backend})

      ;; Specialized agents (map to planner with custom prompts)
      :spec-analyzer (agent/create-planner {:llm-backend llm-backend})
      :designer (agent/create-planner {:llm-backend llm-backend})

      ;; Stub agents (no actual LLM call for now)
      :reviewer nil  ; Will run gates directly
      :releaser nil  ; Marker phase
      :observer nil  ; Metrics collection only

      ;; Skip phase
      :none nil

      ;; Unknown agent type - return nil
      nil)))

;------------------------------------------------------------------------------ Layer 1
;; Gate mapping

(defn create-gates-for-phase
  "Create loop gates from :phase/gates keywords.
   Maps workflow gate keywords to loop gate implementations.

   Gate Mapping:
   - :syntax-valid -> loop/syntax-gate
   - :lint-clean -> loop/lint-gate
   - :tests-pass -> loop/test-gate
   - :no-secrets -> loop/policy-gate :no-secrets
   - Other -> loop/custom-gate (pass-through)

   Arguments:
   - phase: Phase configuration map with :phase/gates
   - _context: Execution context (currently unused)

   Returns sequence of Gate implementations."
  [phase _context]
  (let [gate-keywords (:phase/gates phase [])]
    (mapv (fn [gate-kw]
            (case gate-kw
              :syntax-valid (loop/syntax-gate)
              :lint-clean (loop/lint-gate)
              :tests-pass (loop/test-gate)
              :no-secrets (loop/policy-gate :no-secrets {:policies [:no-secrets]})

              ;; Unknown gates - create pass-through custom gate
              (loop/custom-gate gate-kw
                                (fn [_artifact _ctx]
                                  (loop/pass-result gate-kw :custom)))))
          gate-keywords)))

;------------------------------------------------------------------------------ Layer 2
;; Task creation

(defn create-task-for-phase
  "Create task for agent from phase config and exec-state.

   Arguments:
   - phase: Phase configuration map
   - exec-state: Current execution state
   - _context: Execution context with input data (currently unused)

   Returns task map suitable for agent execution."
  [phase exec-state _context]
  (let [phase-id (:phase/id phase)
        phase-name (:phase/name phase)
        phase-desc (:phase/description phase)
        input-data (:execution/input exec-state)
        artifacts (:execution/artifacts exec-state [])]

    (task/create-task
     {:task/id (random-uuid)
      :task/type (or (:phase/task-type phase) phase-id)
      :task/title (str phase-name)
      :task/description (or phase-desc (str "Execute phase: " phase-name))
      :task/status :pending
      :task/inputs artifacts
      :task/metadata {:phase-id phase-id
                      :workflow-input input-data}})))

;------------------------------------------------------------------------------ Layer 3
;; Generate function

(defn create-generate-fn
  "Create (fn [task ctx] -> {:artifact map :tokens int}) for loop.

   Arguments:
   - phase-agent: Agent instance created by create-agent-for-phase
   - context: Execution context

   Returns generate function for inner loop."
  [phase-agent context]
  (if phase-agent
    ;; Real agent - invoke and return artifact
    (fn [task _ctx]
      (let [result (agent/invoke phase-agent task context)]
        {:artifact (:artifact result)
         :tokens (or (get-in result [:metrics :tokens]) 0)}))

    ;; No agent (stub) - return empty artifact
    (fn [_task _ctx]
      {:artifact {:artifact/id (random-uuid)
                  :artifact/type :stub
                  :artifact/content {:stub true}}
       :tokens 0})))

;------------------------------------------------------------------------------ Layer 4
;; Repair function

(defn create-repair-fn
  "Create repair function for inner loop.
   The repair function attempts to fix validation failures.

   Arguments:
   - phase-agent: Agent instance
   - context: Execution context

   Returns repair function (fn [artifact errors ctx] -> {:success? bool :artifact map})."
  [phase-agent context]
  (if phase-agent
    ;; Real agent - use agent's repair capability
    (fn [old-artifact errors _ctx]
      (let [result (agent/repair phase-agent old-artifact errors context)]
        {:success? (:success result)
         :artifact (:repaired result old-artifact)
         :tokens-used (or (get-in result [:metrics :tokens]) 0)}))

    ;; No agent - return failure
    (fn [old-artifact _errors _ctx]
      {:success? false
       :artifact old-artifact
       :tokens-used 0})))

;------------------------------------------------------------------------------ Layer 5
;; Artifact building

(defn build-artifact-for-phase
  "Build standard artifact from phase result.

   Arguments:
   - raw-artifact: Raw artifact from generate-fn
   - phase: Phase configuration
   - metrics: Execution metrics

   Returns standard artifact map."
  [raw-artifact phase metrics]
  (let [phase-id (:phase/id phase)
        artifact-id (or (:artifact/id raw-artifact)
                        (get raw-artifact :plan/id)
                        (get raw-artifact :code/id)
                        (get raw-artifact :test/id)
                        (random-uuid))]
    (artifact/build-artifact
     {:id artifact-id
      :type (or (:artifact/type raw-artifact) phase-id)
      :version "1.0.0"
      :content raw-artifact
      :metadata (merge (:phase/metadata phase {})
                       {:phase phase-id
                        :metrics metrics})})))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (require '[ai.miniforge.workflow.loader :as loader])

  ;; Load workflow
  (def workflow-result (loader/load-workflow :simple-test-v1 "1.0.0" {}))
  (def workflow (:workflow workflow-result))

  ;; Get first phase
  (def phase (first (:workflow/phases workflow)))

  ;; Create agent
  (def mock-llm (agent/create-mock-llm {:content "test"}))
  (def ctx {:llm-backend mock-llm})
  (def phase-agent (create-agent-for-phase phase ctx))

  ;; Create gates
  (def gates (create-gates-for-phase phase ctx))

  ;; Create task
  (def exec-state {:execution/input {:task "test"}
                   :execution/artifacts []})
  (def task (create-task-for-phase phase exec-state ctx))

  ;; Create generate function
  (def generate-fn (create-generate-fn phase-agent ctx))
  (generate-fn task ctx)

  ;; Create repair function
  (def repair-fn (create-repair-fn phase-agent ctx))
  (repair-fn {:artifact/id (random-uuid)} [{:code :error}] ctx)

  :end)
