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
  "Per-role static defaults (temperature, max-tokens, budget) for agents.

   Loaded from resources/config/agent/role-defaults.edn so tweaking a
   role's token/cost budget happens in one place rather than across
   inlined Clojure literals. The dynamic per-role :model selection lives
   in agent/core.clj, which composes it with `role-default` to produce
   the full role config."
  (:require [clojure.edn :as edn]
            [clojure.java.io :as io]))

(def ^:private role-defaults-resource
  "config/agent/role-defaults.edn")

(def role-defaults
  "Map of role-keyword → static defaults map (loaded from EDN at ns load).

   Each value carries :temperature, :max-tokens, and :budget."
  (-> role-defaults-resource
      io/resource
      slurp
      edn/read-string))

(defn role-default
  "Return the static defaults for `role`.

   Throws ex-info on unknown roles so misconfiguration fails loud at the
   first lookup rather than producing a nil-merge surprise downstream."
  [role]
  (or (get role-defaults role)
      (throw (ex-info "Unknown agent role"
                      {:role role
                       :known-roles (set (keys role-defaults))}))))

(defn roles
  "Return the set of roles for which static defaults are defined."
  []
  (set (keys role-defaults)))
