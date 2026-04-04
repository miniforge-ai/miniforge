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

(ns ai.miniforge.workflow.protocols.impl.phase-executor
  "Implementation functions for PhaseExecutor protocol.
   Pure functions for executing workflow phases."
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.task.interface :as task]
   [ai.miniforge.loop.interface :as loop]
   [ai.miniforge.artifact.interface :as artifact]
   [ai.miniforge.tool.interface :as tool]))

;------------------------------------------------------------------------------ Layer 0
;; Helper functions

(defn persist-artifact
  "Persist an artifact to the store if available.
   Returns nil on success or error (side effect only)."
  [artifact-store artifact]
  (when artifact-store
    (try
      (artifact/save! artifact-store artifact)
      (catch Exception _e nil))))

(defn persist-and-link-artifact
  "Persist an artifact and link it to a parent.
   Returns nil on success or error (side effect only)."
  [artifact-store artifact parent-id]
  (when artifact-store
    (try
      (artifact/save! artifact-store artifact)
      (artifact/link! artifact-store parent-id (:artifact/id artifact))
      (catch Exception _e nil))))

(defn build-standard-artifact
  "Build a standard artifact from type-specific content."
  [artifact-type content-map metadata]
  (let [artifact-id (or (get content-map (keyword (name artifact-type) "id"))
                        (random-uuid))]
    (artifact/build-artifact
     {:id artifact-id
      :type artifact-type
      :version "1.0.0"
      :content content-map
      :metadata metadata})))

(defn extract-artifact-content
  "Extract artifact content from either old or new format."
  [artifact]
  (or (:artifact/content artifact)
      (:content artifact)))

(defn extract-artifact-id
  "Extract artifact ID from either old or new format."
  [artifact]
  (or (:artifact/id artifact)
      (get-in artifact [:artifact/content :code/id])
      (get-in artifact [:content :code/id])
      (random-uuid)))

(defn filter-artifacts-by-type
  "Filter workflow artifacts by type (handles both old and new format)."
  [workflow-state artifact-type]
  (->> (:workflow/artifacts workflow-state)
       (filter #(or (= artifact-type (:type %))
                    (= artifact-type (:artifact/type %))))))

(defn run-agent-with-loop
  "Run an agent with simple loop and return result.
   Pure function that orchestrates agent execution."
  [agent-constructor task context max-iterations]
  (let [agent-instance (agent-constructor)
        tracking-context (tool/attach-invocation-tracking context)
        invoke-args (or (:invoke-args tracking-context) task)
        generate-fn (fn [_task _ctx]
                      (let [result (agent/invoke agent-instance invoke-args tracking-context)]
                        {:artifact (:artifact result)
                         :tokens (get result :tokens 0)}))
        result (loop/run-simple task
                                generate-fn
                                (merge tracking-context
                                       {:max-iterations max-iterations}))
        tool-invocations (tool/tool-invocations tracking-context)]
    (assoc result :tool/invocations tool-invocations)))

;------------------------------------------------------------------------------ Layer 1
;; Plan phase implementation

(defn execute-plan-phase
  "Execute plan phase - creates implementation plan from spec."
  [llm-backend workflow-state context]
  (let [spec (:workflow/spec workflow-state)
        artifact-store (:artifact-store context)
        task (task/create-task
              {:task/id (random-uuid)
               :task/type :plan
               :task/title (str "Plan: " (:title spec))
               :task/description (:description spec)
               :task/status :pending})
        result (run-agent-with-loop
                #(agent/create-planner {:llm-backend llm-backend})
                task context 3)]
    (if (:success result)
      (let [plan-artifact (:artifact result)
            standard-artifact (build-standard-artifact
                               :plan
                               plan-artifact
                               {:phase :plan :spec-title (:title spec)})]
        (persist-artifact artifact-store standard-artifact)
        {:success? true
         :artifacts [standard-artifact]
         :errors []
         :metrics (:metrics result)})
      {:success? false
       :artifacts []
       :errors [{:type :plan-failed
                 :message (get result :error "Planning failed")}]
       :metrics (:metrics result)})))

;------------------------------------------------------------------------------ Layer 2
;; Implement phase implementation

(defn process-implementation-plan
  "Process a single plan artifact and return implementation result.
   Pure function that coordinates implementation for one plan."
  [plan-artifact llm-backend context]
  (let [plan-content (extract-artifact-content plan-artifact)
        task (task/create-task
              {:task/id (random-uuid)
               :task/type :implement
               :task/title "Implement from plan"
               :task/description (str plan-content)
               :task/status :pending})
        result (run-agent-with-loop
                #(agent/create-implementer {:llm-backend llm-backend})
                task context 5)]
    (if (:success result)
      {:artifact (:artifact result)
       :metrics (:metrics result)}
      {:error {:type :implement-failed
               :message (get result :error "Implementation failed")}
       :metrics (:metrics result)})))

(defn code-artifact->standard-artifact
  "Convert a CodeArtifact to standard artifact format.

   In the new environment model, code changes live in the execution
   environment's git working tree and are NOT serialized into artifacts.
   :file-count defaults to 0 when :code/files is absent (new model);
   code provenance is derived from the PR diff at release time."
  [code-artifact]
  (build-standard-artifact
   :code
   code-artifact
   {:phase :implement
    :language (:code/language code-artifact)
    :file-count (count (get code-artifact :code/files []))}))

(defn execute-implement-phase
  "Execute implement phase - generates code from plans."
  [llm-backend workflow-state context]
  (let [plan-artifacts (filter-artifacts-by-type workflow-state :plan)
        artifact-store (:artifact-store context)
        results (map #(process-implementation-plan % llm-backend context)
                     plan-artifacts)
        artifacts (keep (fn [r]
                          (when-let [art (:artifact r)]
                            (let [standard-art (code-artifact->standard-artifact art)]
                              (persist-artifact artifact-store standard-art)
                              standard-art)))
                        results)
        errors (keep :error results)
        metrics (reduce (fn [m r] (merge-with + m (:metrics r)))
                        {:tokens 0 :cost-usd 0.0 :duration-ms 0}
                        results)]
    {:success? (empty? errors)
     :artifacts artifacts
     :errors errors
     :metrics metrics}))

;------------------------------------------------------------------------------ Layer 3
;; Verify phase implementation

(defn process-verification-for-code
  "Process verification (test generation) for a single code artifact.
   Pure function that coordinates test generation."
  [code-artifact llm-backend context]
  (let [artifact-id (extract-artifact-id code-artifact)
        code-content (extract-artifact-content code-artifact)
        task (task/create-task
              {:task/id (random-uuid)
               :task/type :test
               :task/title "Generate tests"
               :task/description (str "Generate tests for code artifact " artifact-id)
               :task/status :pending
               :task/inputs [artifact-id]})
        test-context (assoc context :code-artifact code-content)
        result (run-agent-with-loop
                #(agent/create-tester {:llm-backend llm-backend})
                task
                (merge test-context {:invoke-args {:code code-content}})
                3)]
    (if (:success result)
      {:artifact (:artifact result)
       :parent-id artifact-id
       :metrics (:metrics result)}
      {:error {:type :verify-failed
               :message (get result :error "Verification failed")}
       :metrics (:metrics result)})))

(defn execute-verify-phase
  "Execute verify phase - generates tests for code artifacts."
  [llm-backend workflow-state context]
  (let [code-artifacts (filter-artifacts-by-type workflow-state :code)
        artifact-store (:artifact-store context)
        results (map #(process-verification-for-code % llm-backend context)
                     code-artifacts)
        artifacts (keep (fn [r]
                          (when-let [test-artifact (:artifact r)]
                            (let [parent-id (:parent-id r)
                                  standard-artifact (build-standard-artifact
                                                     :test
                                                     test-artifact
                                                     {:phase :verify
                                                      :parents [parent-id]})]
                              (persist-and-link-artifact artifact-store standard-artifact parent-id)
                              standard-artifact)))
                        results)
        errors (keep :error results)
        metrics (reduce (fn [m r] (merge-with + m (:metrics r)))
                        {:tokens 0 :cost-usd 0.0 :duration-ms 0}
                        results)]
    {:success? (empty? errors)
     :artifacts artifacts
     :errors errors
     :metrics metrics}))
