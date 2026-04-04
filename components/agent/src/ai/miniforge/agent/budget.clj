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

(ns ai.miniforge.agent.budget
  "Shared agent budget policy and resolution helpers."
  (:require
   [ai.miniforge.agent.core :as core]))

;------------------------------------------------------------------------------ Layer 0
;; Canonical role budget policy

(def ^:private role-aliases
  "Specialized agent role names that map to canonical role configs."
  {:releaser :release})

(defn- canonical-role
  "Map specialized role names to the canonical role key used in role configs."
  [role]
  (get role-aliases role role))

(defn role-budget
  "Return the canonical budget map for an agent role.
   Falls back to the implementer budget for unknown roles."
  [role]
  (or (get-in core/default-role-configs [(canonical-role role) :budget])
      (get-in core/default-role-configs [:implementer :budget])))

(defn role-cost-budget-usd
  "Return the canonical dollar budget for an agent role."
  [role]
  (:cost-usd (role-budget role)))

(defn apply-default-budget
  "Ensure a config map carries the canonical role budget unless explicitly overridden."
  [role config]
  (update config :budget #(or % (role-budget role))))

;------------------------------------------------------------------------------ Layer 1
;; Runtime request resolution

(defn resolve-cost-budget-usd
  "Resolve the effective dollar budget for an agent invocation.

   Precedence:
   1. Explicit config budget on the agent
   2. Budget provided in execution context
   3. Canonical role budget"
  [role config context]
  (or (get-in config [:budget :cost-usd])
      (get-in context [:budget :cost-usd])
      (role-cost-budget-usd role)))

(comment
  (role-budget :planner)
  (role-budget :releaser)
  (role-cost-budget-usd :tester)
  (apply-default-budget :implementer {:max-tokens 8000})
  (resolve-cost-budget-usd :release {:budget {:cost-usd 0.5}} {}))
