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

(ns ai.miniforge.agent.role-config
  "Per-role agent defaults loaded from EDN.

   Two distinct sets of defaults live here, with different consumers:

   - role-defaults (config/agent/role-defaults.edn): system-wide
     temperature/max-tokens/budget used by core.clj's
     default-role-configs (the make-base-agent code path).

   - agent-llm-defaults (config/agent/agent-llm-defaults.edn):
     operative LLM-call config used by each specialized create-{role}
     factory fn (planner.clj, implementer.clj, etc.).

   OPSV (N7) will eventually converge these — see
   work/n07-opsv-agent-budgets.spec.edn."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(defn- load-edn
  "Read an EDN resource by classpath path."
  [resource-path]
  (-> resource-path io/resource slurp edn/read-string))

(defn- lookup-or-throw
  "Return (get m k); throw ex-info with `kind` context on miss."
  [m k kind]
  (or (get m k)
      (throw (ex-info (str "Unknown agent role for " kind)
                      {:role k
                       :kind kind
                       :known-roles (set (keys m))}))))

;------------------------------------------------------------------------------ Layer 0
;; System-wide role defaults (consumed by core.clj/default-role-configs)

(def role-defaults
  "Map of role-keyword → static defaults map (loaded from EDN at ns load).

   Each value carries :temperature, :max-tokens, and :budget."
  (load-edn "config/agent/role-defaults.edn"))

(defn role-default
  "Return the static defaults for `role`.

   Throws ex-info on unknown roles so misconfiguration fails loud at the
   first lookup rather than producing a nil-merge surprise downstream."
  [role]
  (lookup-or-throw role-defaults role "role-default"))

(defn roles
  "Return the set of roles for which static defaults are defined."
  []
  (set (keys role-defaults)))

;------------------------------------------------------------------------------ Layer 1
;; Per-LLM-call defaults (consumed by each specialized create-{role} factory)

(def agent-llm-defaults
  "Map of role-keyword → per-LLM-call config (loaded from EDN at ns load).

   Each value carries :temperature and :max-tokens. These are the
   operative defaults applied by each create-{role} factory fn before
   merging in the caller's :config opts."
  (load-edn "config/agent/agent-llm-defaults.edn"))

(defn agent-llm-default
  "Return the per-LLM-call config defaults for `role`.

   Throws ex-info on unknown roles."
  [role]
  (lookup-or-throw agent-llm-defaults role "agent-llm-default"))
