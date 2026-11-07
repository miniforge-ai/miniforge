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
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.task-executor.interface :as task-executor]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} completion-only-backends
  "LLM backends that call a completion API over HTTP (:cmd \"http\"). They
   have no tools, so a prompt to one can only produce text. The CLI
   backends (claude, codex, cursor, opencode) run agents that can read
   and write files: codex runs every call with workspace-write and no
   approvals, and claude honours the user's own permission settings."
  (into #{} (keep (fn [[backend {:keys [cmd]}]] (when (= "http" (str cmd)) backend)))
        llm/backends))

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
     the model's text, or nil when the call fails. Only a completion-only
     backend is asked; with an agent backend it returns nil, so comments
     are classified by heuristics and questions go unanswered, rather
     than letting a comment's text drive an agent that can write files
     outside the fix loop;
   - with a fix task and loop context (fixing a change request): returns
     the implementer agent's {:artifact :tokens}, the same generate-fn the
     PR lifecycle controller gets from the task runner.
   `model` is the configured model id, sent with each prompt; the HTTP
   backends need one."
  [llm-client model]
  (let [fix-fn (task-executor/create-generate-fn llm-client)
        text? (contains? completion-only-backends (llm/client-backend llm-client))]
    (fn
      ([prompt]
       (when text?
         (let [response (llm/chat llm-client prompt (cond-> {} model (assoc :model model)))]
           (when (llm/success? response)
             (llm/get-content response)))))
      ([task ctx]
       (fix-fn task ctx)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} monitor-agent-opts
  "The :generate-fn and :trusted-authors entries for create-pr-monitor,
   from the command's --trusted-authors option and the configured LLM
   backend. An unknown backend throws, so the monitor does not start
   without the generate-fn it would need to act. Prints a notice when the
   backend is an agent, so comment classification and question answers
   are off."
  [{:keys [trusted-authors]}]
  (let [client (context/create-llm-client nil nil true)
        backend (llm/client-backend client)]
    (when-not (contains? completion-only-backends backend)
      (display/print-info (messages/t :pr/monitor-text-backend-unsafe {:backend (name backend)})))
    {:generate-fn (monitor-generate-fn client (get-in (config/load-config) [:llm :model]))
     :trusted-authors (parse-trusted-authors trusted-authors)}))
