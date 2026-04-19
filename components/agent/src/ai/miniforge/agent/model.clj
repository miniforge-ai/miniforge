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

(ns ai.miniforge.agent.model
  "Shared agent model policy and configuration helpers."
  (:require
   [ai.miniforge.config.interface :as config]))

;------------------------------------------------------------------------------ Layer 0
;; Canonical role model policy

(def ^:private default-agent-models
  "Fallback model defaults when user config does not specify agent defaults.

   Thinking defaults to Claude because non-Claude CLI backends (codex,
   cursor-agent, gh-copilot, etc.) don't yet ship structured plans back
   through their stream parsers. Tracked in
   work/multi-backend-cli-parity.spec.edn."
  {:thinking "claude-opus-4-6"
   :execution "claude-sonnet-4-6"})


(def ^:private thinking-roles
  "Roles that default to the higher-reasoning model tier."
  #{:planner :architect})

(defn- load-configured-agent-models
  "Load agent default models from merged user configuration."
  []
  (let [cfg (config/load-merged-config)]
    {:thinking (or (get-in cfg [:agents :default-models :thinking])
                   (:thinking default-agent-models))
     :execution (or (get-in cfg [:agents :default-models :execution])
                    (get-in cfg [:llm :model])
                    (:execution default-agent-models))}))

(defn default-model-for-role
  "Return the configured default model id for an agent role."
  [role]
  (let [{:keys [thinking execution]} (load-configured-agent-models)]
    (if (thinking-roles role)
      thinking
      execution)))

(defn apply-default-model
  "Ensure a config map carries the configured default model for a role unless
   explicitly overridden."
  [role config]
  (update config :model #(or % (default-model-for-role role))))


(defn resolve-llm-client-for-role
  "Resolve or create an LLM client appropriate for an agent role.
   If the role's default model uses a different backend than the provided client,
   creates a new client with the correct backend.

   Arguments:
     role           - Agent role keyword (:planner, :implementer, etc.)
     provided-client - The shared LLM client from workflow context (may be nil)"
  [role provided-client]
  (if (nil? provided-client)
    nil
    (let [model-id (default-model-for-role role)
        backend-for-model (requiring-resolve 'ai.miniforge.llm.interface/backend-for-model)
        client-backend (requiring-resolve 'ai.miniforge.llm.interface/client-backend)
        create-client (requiring-resolve 'ai.miniforge.llm.interface/create-client)
        needed-backend (backend-for-model model-id)
        current-backend (client-backend provided-client)]
      (if (= current-backend needed-backend)
        (assoc-in provided-client [:config :model] model-id)
        (create-client {:backend needed-backend :model model-id})))))


(comment
  (default-model-for-role :planner)
  (default-model-for-role :implementer)
  (apply-default-model :tester {:temperature 0.2}))
