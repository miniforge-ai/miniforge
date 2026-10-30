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
(ns ai.miniforge.cli.main.commands.pr-monitor-agent
  "What the PR monitor needs to act on review comments: the logins it
   trusts beyond GitHub's OWNER/MEMBER/COLLABORATOR associations, and the
   LLM generate-fn it classifies comments, answers questions and fixes
   change requests with."
  (:require
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.task-executor.interface :as task-executor]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} text-reply-max-turns
  "Agent turns allowed when the monitor asks for text (classifying a
   comment, answering a question): one reply, no tool round trips."
  1)

(defn ^{:stratum 0} parse-trusted-authors
  "Logins from a comma-separated --trusted-authors value, as a set; empty
   when the flag is absent."
  [value]
  (->> (str/split (str value) #",")
       (map str/trim)
       (remove str/blank?)
       set))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} monitor-generate-fn
  "generate-fn for the PR monitor, which calls it two ways:
   - with a prompt (classifying a comment, answering a question): returns
     the model's text, or nil when the call fails;
   - with a fix task and loop context (fixing a change request): returns
     the implementer agent's {:artifact :tokens}, the same generate-fn the
     PR lifecycle controller gets from the task runner."
  [llm-client]
  (let [fix-fn (task-executor/create-generate-fn llm-client)]
    (fn
      ([prompt]
       (let [response (llm/chat llm-client prompt {:max-turns text-reply-max-turns})]
         (when (llm/success? response)
           (llm/get-content response))))
      ([task ctx]
       (fix-fn task ctx)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} monitor-agent-opts
  "The :generate-fn and :trusted-authors entries for create-pr-monitor,
   from the command's --trusted-authors option and the configured LLM
   backend. An unknown backend throws, so the monitor does not start
   without the generate-fn it would need to act."
  [{:keys [trusted-authors]}]
  {:generate-fn (monitor-generate-fn (context/create-llm-client nil nil true))
   :trusted-authors (parse-trusted-authors trusted-authors)})
