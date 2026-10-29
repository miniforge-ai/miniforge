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
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0

;; Canonical role model policy
(def ^{:stratum 0} ^:private default-agent-models
  "Fallback model defaults when user config does not specify agent defaults.

   Thinking defaults to Claude because non-Claude CLI backends (codex,
   cursor-agent, gh-copilot, etc.) don't yet ship structured plans back
   through their stream parsers. Tracked in
   work/multi-backend-cli-parity.spec.edn."
  {:thinking "claude-opus-4-6"
   :execution "claude-sonnet-4-6"})

(def ^{:stratum 0} ^:private thinking-roles
  "Roles that default to the higher-reasoning model tier."
  #{:planner :architect})

(defn- ^{:stratum 0} model-id-for-backend
  "Return the form of `model-id` to hand to a client on `backend`.

   A provider-prefixed id such as \"anthropic/claude-sonnet-4-6\" is the
   form routing backends (opencode, openrouter) take, and it is what the
   shipped default config uses. When the bare id after the prefix is a
   catalog model of `backend` itself (\"claude-sonnet-4-6\" on :claude),
   return the bare id, which that backend accepts. Otherwise return
   `model-id` unchanged."
  [model-id backend]
  (let [bare-id (second (re-matches #"[^/]+/(.+)" (str model-id)))]
    (if (and bare-id (= backend (llm/backend-for-model bare-id nil)))
      bare-id
      model-id)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} load-configured-agent-models
  "Load agent default models from merged user configuration."
  []
  (let [cfg (config/load-merged-config)]
    {:thinking (or (get-in cfg [:agents :default-models :thinking])
                   (:thinking default-agent-models))
     :execution (or (get-in cfg [:agents :default-models :execution])
                    (get-in cfg [:llm :model])
                    (:execution default-agent-models))}))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} default-model-for-role
  "Return the configured default model id for an agent role."
  [role]
  (let [{:keys [thinking execution]} (load-configured-agent-models)]
    (if (thinking-roles role)
      thinking
      execution)))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} apply-default-model
  "Ensure a config map carries the configured default model for a role unless
   explicitly overridden."
  [role config]
  (update config :model #(or % (default-model-for-role role))))

(defn ^{:stratum 3} resolve-llm-client-for-role
  "Resolve or create an LLM client appropriate for an agent role.

   Switches backend only when the model catalog lists the role's default
   model under a different backend than the provided client's (e.g.
   \"gpt-5.4\" on a :claude client gets a :codex client). A model id the
   catalog does not list, including provider-prefixed ids such as
   \"anthropic/claude-sonnet-4-6\", keeps the provided client's backend:
   the catalog says nothing about which backend serves it, so the
   configured backend is the only evidence.

   Arguments:
     role           - Agent role keyword (:planner, :implementer, etc.)
     provided-client - The shared LLM client from workflow context (may be nil)"
  [role provided-client]
  (when provided-client
    (let [model-id        (default-model-for-role role)
          catalog-backend (llm/backend-for-model model-id nil)
          current-backend (llm/client-backend provided-client)]
      (if (and catalog-backend (not= catalog-backend current-backend))
        (llm/create-client {:backend catalog-backend :model model-id})
        (assoc-in provided-client [:config :model]
                  (model-id-for-backend model-id current-backend))))))

(comment
  (default-model-for-role :planner)
  (default-model-for-role :implementer)
  (apply-default-model :tester {:temperature 0.2}))
