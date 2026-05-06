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

(ns ai.miniforge.progress-detector.tool-profile
  "Tool-profile registration API.

   Architectural intent: progress-detector does NOT own knowledge of
   which tools exist or what their characteristics are. Each component
   that vendors a tool the agent can call (LLM client, MCP servers, the
   adapter layer) is responsible for contributing a profile via
   `register!` at startup.

   Stage 1 ships an empty default registry. The component layout for
   tool-profile data lives with the components that vendor each tool —
   `adapter-claude-code` for the Claude CLI native tools, `agent` for
   MCP-driven tools, etc. (Stage 2 wires the contributions; Stage 1
   provides the registration mechanism.)

   Public API (pure unless suffixed with `!`):
     make-registry         - create a new empty registry atom
     register!             - add or override a profile (returns new value)
     unregister!           - remove a profile by tool-id
     lookup                - retrieve profile by tool-id (or nil)
     determinism-of        - :determinism level (defaults to :unstable)
     categories-of         - anomaly category set (defaults to #{})
     all-tool-ids          - sorted vector of registered ids
     validate-all          - per-entry ToolProfile validation report

   The framework holds a process-wide `default-registry` atom for
   convenience; components contribute to it at load time. Tests
   should use `make-registry` and pass the result explicitly to the
   2-arity query functions to avoid global state."
  (:require
   [ai.miniforge.progress-detector.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Registry construction

(defn make-registry
  "Create a fresh empty registry atom. Components contribute via
   `register!`; queries use `lookup` / `determinism-of` / etc."
  []
  (atom {}))

(defonce default-registry
  ;; Process-wide registry. Components contribute at load time:
  ;;   (tool-profile/register! tool-profile/default-registry
  ;;                           {:tool/id :tool/Read :determinism ...})
  ;; Tests should NOT use this — make their own via make-registry.
  (make-registry))

;------------------------------------------------------------------------------ Layer 1
;; Mutating API (registration)

(defn register!
  "Add or overwrite a profile for `(:tool/id profile)` in `registry-atom`.

   Arguments:
     registry-atom - atom holding the registry map
     profile       - map satisfying the ToolProfile schema

   Returns the new registry map. Throws via ex-info if profile is
   missing :tool/id or fails schema validation — caller bug."
  [registry-atom profile]
  (let [tool-id (:tool/id profile)]
    (when-not tool-id
      (throw (ex-info "Tool profile must include :tool/id"
                      {:profile profile})))
    (when-not (schema/valid-tool-profile? profile)
      (throw (ex-info "Tool profile fails ToolProfile schema validation"
                      {:profile profile
                       :errors  (schema/explain-tool-profile profile)}))))
  (swap! registry-atom assoc (:tool/id profile) profile))

(defn unregister!
  "Remove the profile for `tool-id` from `registry-atom`.
   Returns the new registry map."
  [registry-atom tool-id]
  (swap! registry-atom dissoc tool-id))

;------------------------------------------------------------------------------ Layer 2
;; Query API (pure; accepts atom or unwrapped map)

(defn- registry-value
  "Unwrap an atom-or-map registry argument."
  [registry-or-atom]
  (if (instance? clojure.lang.IDeref registry-or-atom)
    @registry-or-atom
    registry-or-atom))

(defn lookup
  "Return the profile for `tool-id` from `registry`, or nil.

   Arguments:
     tool-id  - qualified keyword e.g. :tool/Read
     registry - registry atom or unwrapped map (defaults to default-registry)"
  ([tool-id]
   (lookup tool-id default-registry))
  ([tool-id registry]
   (get (registry-value registry) tool-id)))

(defn determinism-of
  "Return the :determinism level for `tool-id`, or :unstable if unknown.

   :unstable is the safe default — unknown tools are treated as heuristic
   signals only, never as mechanical-eligible loops."
  ([tool-id]
   (determinism-of tool-id default-registry))
  ([tool-id registry]
   (get (lookup tool-id registry) :determinism :unstable)))

(defn categories-of
  "Return the set of anomaly categories for `tool-id`, or #{} if unknown."
  ([tool-id]
   (categories-of tool-id default-registry))
  ([tool-id registry]
   (get (lookup tool-id registry) :anomaly/categories #{})))

(defn all-tool-ids
  "Return sorted vector of all registered tool-id keywords."
  ([]
   (all-tool-ids default-registry))
  ([registry]
   (vec (sort (keys (registry-value registry))))))

;------------------------------------------------------------------------------ Layer 2
;; Audit

(defn validate-all
  "Validate every entry in `registry` against the ToolProfile schema.

   Returns: Map of tool-id -> {:valid? bool :errors humanized-or-nil}.
   Uses the ToolProfile humanizer (NOT the Anomaly one) so error
   messages cite the right schema's fields."
  [registry]
  (reduce-kv
   (fn [acc tool-id profile]
     (let [valid? (schema/valid-tool-profile? profile)]
       (assoc acc tool-id
              {:valid? valid?
               :errors (when-not valid?
                         (schema/explain-tool-profile profile))})))
   {}
   (registry-value registry)))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Test-style: explicit registry, no global state
  (def reg (make-registry))
  (register! reg {:tool/id :tool/Read
                  :determinism :stable-with-resource-version
                  :anomaly/categories #{:anomalies.agent/tool-loop}})
  (lookup :tool/Read reg)
  ;; => {:tool/id :tool/Read :determinism ... :anomaly/categories #{...}}

  (determinism-of :tool/Read reg)        ; => :stable-with-resource-version
  (determinism-of :tool/UnknownTool reg) ; => :unstable (safe default)

  ;; Production-style: components contribute to default-registry at load
  (register! default-registry
             {:tool/id :tool/Bash
              :determinism :environment-dependent})

  (validate-all reg)
  ;; => {:tool/Read {:valid? true :errors nil}}

  :leave-this-here)
