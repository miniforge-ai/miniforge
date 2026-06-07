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

(ns ai.miniforge.task-executor.generate
  "Factory for generate-fn closures wrapping agent + inner loop.

  Creates reusable generation functions that can be used for both:
  - Initial code generation in the task runner
  - Fix loops in the PR lifecycle controller

  This ensures consistency: both use loop/run-simple with the same agent backend."
  (:require [ai.miniforge.loop.interface :as loop]
            [ai.miniforge.agent.interface :as agent]
            [clojure.string :as str]))

(defn format-task-prompt
  [task context]
  (let [{:keys [description files dependencies acceptance-criteria]} task
        {:keys [worktree-path base-commit]} context]
    (str "# Task: " description "\n\n"
         (when files
           (str "## Files to modify:\n"
                (str/join "\n" (map #(str "- " %) files))
                "\n\n"))
         (when dependencies
           (str "## This task depends on:\n"
                (str/join "\n" (map #(str "- " %) dependencies))
                "\n\n"))
         (when acceptance-criteria
           (str "## Acceptance criteria:\n" acceptance-criteria "\n\n"))
         "## Environment:\n"
         "- Working directory: " worktree-path "\n"
         "- Base commit: " base-commit "\n")))

(defn create-generate-fn
  [llm-backend & {:keys [logger event-stream workflow-id max-iterations]}]
  (fn [task context]
    (let [task-map (if (map? task)
                     task
                     {:task/id (str (random-uuid))
                      :task/type :implement
                      :description (str task)})
          prompt (format-task-prompt task-map context)
          loop-context (cond-> {:max-iterations (or max-iterations 10)
                                :initial-prompt prompt
                                :llm-backend llm-backend}
                         logger (assoc :logger logger)
                         event-stream (assoc :event-stream event-stream)
                         workflow-id (assoc :workflow-id workflow-id))
          ;; Create implementer agent with LLM backend
          implementer (agent/create-implementer {:llm-backend llm-backend
                                                  :logger logger})
          result (loop/run-simple task-map
                                  (fn [t ctx]
                                    ;; Inner generate-fn that invokes implementer agent
                                    (let [agent-result (agent/invoke implementer ctx t)]
                                      {:artifact (:artifact agent-result)
                                       :tokens (get agent-result :tokens 0)}))
                                  loop-context)]
      {:artifact (:artifact result)
       :tokens (or (:total-tokens result) (:tokens result) 0)})))
