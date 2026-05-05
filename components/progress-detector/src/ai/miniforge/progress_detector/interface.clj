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

(ns ai.miniforge.progress-detector.interface
  "Progress-detector public API.

   Integrates the Detector protocol, anomaly schema, tool-profile registry,
   and config merge semantics into a single cohesive surface.

   ## Concepts

   An **observation** describes one tool-invocation event:
     {:tool/id :tool/bash :seq 1 :timestamp inst :tool/duration-ms 80}

   A **detector** implements the Detector protocol — a pure reducer:
     init    : config -> initial-state
     observe : (state, observation) -> state'

   An **anomaly** is a validated map surfaced in state :anomalies:
     {:anomaly/id \"uuid\" :anomaly/category :anomaly.category/loop ...}

   The **tool-profile registry** maps tool-id keywords to profiles that
   describe each tool's determinism level and detectable anomaly categories.

   **Config layers** compose via overlay resolution:
     :inherit / :disable / :enable / :tune

   ## Quick start

     (require '[ai.miniforge.progress-detector.interface :as pd])

     ;; 1. Prepare detector
     (def det (pd/null-detector))
     (def state (pd/init det {}))

     ;; 2. Feed observations
     (def obs {:tool/id :tool/bash :seq 1
               :timestamp (java.time.Instant/now)
               :tool/duration-ms 120})
     (def state' (pd/observe det state obs))

     ;; 3. Read anomalies
     (:anomalies state')    ; => []

     ;; 4. Tool profile lookup
     (pd/tool-determinism :tool/agent)   ; => :nondeterministic

     ;; 5. Config merge
     (pd/resolve-config
       [{:config/params {:window-size 5}}
        {:config/directive :tune :config/params {:window-size 10}}])
     ;; => {:detector/enabled? true :config/params {:window-size 10} ...}"
  (:require
   [ai.miniforge.progress-detector.protocol    :as proto]
   [ai.miniforge.progress-detector.schema      :as schema]
   [ai.miniforge.progress-detector.tool-profile :as tp]
   [ai.miniforge.progress-detector.config      :as cfg]))

;------------------------------------------------------------------------------ Layer 0
;; Layer 0 — Detector protocol (re-exported)

(def null-detector
  "Return a NullDetector that never flags any anomaly.
   Useful as a no-op placeholder or base for detector pipelines.

   Returns: NullDetector record satisfying the Detector protocol."
  proto/null-detector)

(def multi-detector
  "Compose multiple detectors into one fanout detector.
   Observations are forwarded to all children; anomalies are merged.

   Arguments:
     detectors - seq of Detector implementations

   Returns: MultiDetector record."
  proto/multi-detector)

(defn init
  "Produce initial detector state from config.

   Arguments:
     detector - Detector protocol implementation
     config   - map of detector-specific configuration

   Returns: Initial state map with :anomalies [] and :observations []."
  [detector config]
  (proto/init detector config))

(defn observe
  "Pure reducer: fold one observation into accumulated detector state.

   Argument order is [detector state observation] — detector first
   so this works as a 2-arity reducer step via (partial observe det)
   in reduce/transduce contexts. For pipelining a single state
   forward, use `let` bindings (or `as->`) rather than `->` — `->`
   would put state in the detector slot.

   Arguments:
     detector    - Detector protocol implementation (NOT the state)
     state       - current state (result of init or prior observe);
                   the value to thread forward through the reduction
     observation - tool invocation event map

   Contract (per the spec's pure-reducer requirement):
     - state argument MUST NOT be mutated
     - return value MUST be threaded back into the next observe call
     - implementations MUST NOT throw — anomalies surface as data on
       the returned state's :anomalies vector

   Returns: New state map (same shape as init's return) with
   :anomalies updated to include any newly detected anomaly maps."
  [detector state observation]
  (proto/observe detector state observation))

(def reduce-observations
  "Fold a seq of observations through a detector, returning final state.

   Arguments:
     detector     - Detector implementation
     config       - config map passed to init
     observations - seq of observation maps

   Returns: Final state after all observations are folded."
  proto/reduce-observations)

;------------------------------------------------------------------------------ Layer 1
;; Layer 1 — Anomaly schema (re-exported)

(def valid-anomaly?
  "Return true if m satisfies the Anomaly schema."
  schema/valid-anomaly?)

(def explain-anomaly
  "Return humanized error map if m fails Anomaly validation, else nil."
  schema/explain-anomaly)

(def valid-observation?
  "Return true if m satisfies the Observation schema."
  schema/valid-observation?)

(def explain-observation
  "Return humanized error map if m fails Observation validation, else nil."
  schema/explain-observation)

(def valid-tool-profile?
  "Return true if m satisfies the ToolProfile schema."
  schema/valid-tool-profile?)

(def explain-tool-profile
  "Return humanized error map if m fails ToolProfile validation, else nil."
  schema/explain-tool-profile)

(def valid-detector-config?
  "Return true if m satisfies the DetectorConfig schema."
  schema/valid-detector-config?)

(def explain-detector-config
  "Return humanized error map if m fails DetectorConfig validation, else nil."
  schema/explain-detector-config)

;; Expose schema vars directly for advanced usage
(def Anomaly          schema/Anomaly)
(def Observation      schema/Observation)
(def ToolProfile      schema/ToolProfile)
(def DetectorConfig   schema/DetectorConfig)

;------------------------------------------------------------------------------ Layer 2
;; Layer 2 — Tool-profile registry (re-exported)

(def load-tool-registry
  "Load the EDN-driven tool-profile registry from classpath.

   Optionally accepts a resource-path override.

   Returns: map of tool-id-keyword -> profile-map."
  tp/load-registry)

(defn tool-lookup
  "Return the profile for tool-id from the registry, or nil.

   Arguments:
     tool-id  - qualified keyword e.g. :tool/bash
     registry - (optional) registry map; defaults to built-in registry"
  ([tool-id]
   (tp/lookup tool-id))
  ([tool-id registry]
   (tp/lookup tool-id registry)))

(defn tool-determinism
  "Return the :determinism level for tool-id, or :volatile if unknown.

   Arguments:
     tool-id  - qualified keyword
     registry - (optional) registry map"
  ([tool-id]
   (tp/determinism-of tool-id))
  ([tool-id registry]
   (tp/determinism-of tool-id registry)))

(defn tool-categories
  "Return the anomaly category set for tool-id, or #{} if unknown.

   Arguments:
     tool-id  - qualified keyword
     registry - (optional) registry map"
  ([tool-id]
   (tp/categories-of tool-id))
  ([tool-id registry]
   (tp/categories-of tool-id registry)))

(defn tool-detector-kind
  "Return the :detector/kind for tool-id, or :detector.kind/shell if unknown.

   Arguments:
     tool-id  - qualified keyword
     registry - (optional) registry map"
  ([tool-id]
   (tp/detector-kind-of tool-id))
  ([tool-id registry]
   (tp/detector-kind-of tool-id registry)))

(def all-tool-ids
  "Return sorted vector of all registered tool-id keywords.

   Arguments:
     registry - (optional) registry map"
  tp/all-tool-ids)

(defn register-tool
  "Return a new registry with a profile added/overwritten for tool-id.

   Arguments:
     registry - existing registry map
     tool-id  - qualified keyword
     profile  - profile map (should satisfy ToolProfile schema)

   Returns: New registry map."
  [registry tool-id profile]
  (tp/register registry tool-id profile))

;------------------------------------------------------------------------------ Layer 2
;; Layer 3 — Config merge semantics (re-exported)

(def resolve-config
  "Resolve a seq of overlay layers into a final config map.

   Layers are applied left-to-right; each may declare:
     :config/directive - :inherit | :disable | :enable | :tune
     :config/params    - detector-specific tuning knobs

   Returns: Resolved config map with :detector/enabled?, :config/params,
   :config/directives."
  cfg/merge-config)

(def apply-config-directive
  "Apply one overlay layer onto an accumulated config map.

   Arguments:
     accumulated - current resolved config
     layer       - overlay map

   Returns: New config map."
  cfg/apply-directive)

(def config-enabled?
  "Return true if resolved config marks the detector as enabled.
   Defaults to true if :detector/enabled? is absent."
  cfg/enabled?)

(def effective-config-params
  "Return the merged :config/params from a resolved config."
  cfg/effective-params)

(def config-overlay
  "Overlay child config onto parent config.
   Shorthand for (resolve-config [parent child])."
  cfg/overlay)

;------------------------------------------------------------------------------ Layer 3
;; Layer 4 — Convenience constructors and helpers

(defn make-observation
  "Construct a valid observation map with required fields.

   Arguments:
     tool-id      - qualified keyword (e.g. :tool/bash)
     seq-num      - monotonic integer sequence number
     timestamp    - java.time.Instant of this observation
     opts         - (optional) map of additional fields:
                      :tool/duration-ms :tool/input :tool/output :tool/error?

   Returns: observation map."
  ([tool-id seq-num timestamp]
   (make-observation tool-id seq-num timestamp {}))
  ([tool-id seq-num timestamp opts]
   (merge {:tool/id   tool-id
           :seq       seq-num
           :timestamp timestamp}
          opts)))

(defn current-anomalies
  "Extract the :anomalies vector from detector state.

   Arguments:
     state - detector state map

   Returns: Vector of anomaly maps (possibly empty)."
  [state]
  (get state :anomalies []))

(defn anomalies-by-severity
  "Return anomalies from state filtered to the given severity.

   Arguments:
     state    - detector state map
     severity - :anomaly.severity/critical | :warning | :info

   Returns: Vector of matching anomaly maps."
  [state severity]
  (filterv #(= severity (:anomaly/severity %))
           (current-anomalies state)))

(defn critical-anomalies
  "Return only :anomaly.severity/critical anomalies from state.

   Arguments:
     state - detector state map

   Returns: Vector of critical anomaly maps."
  [state]
  (anomalies-by-severity state :anomaly.severity/critical))

;------------------------------------------------------------------------------ Rich Comment
;; Rich comment

(comment
  ;; Smoke-test the full pipeline
  (let [det  (null-detector)
        cfg  (resolve-config [{:config/params {:window-size 5}}
                              {:config/directive :tune
                               :config/params {:window-size 10}}])
        st0  (init det (effective-config-params cfg))
        obs1 (make-observation :tool/bash 1 (java.time.Instant/now)
                               {:tool/duration-ms 120})
        obs2 (make-observation :tool/write 2 (java.time.Instant/now)
                               {:tool/duration-ms 30})
        stf  (reduce-observations det (effective-config-params cfg) [obs1 obs2])]
    {:enabled?  (config-enabled? cfg)
     :params    (effective-config-params cfg)
     :anomalies (current-anomalies stf)})
  ;; => {:enabled? true :params {:window-size 10} :anomalies []}

  ;; Tool-profile queries
  (tool-determinism :tool/agent)    ; => :nondeterministic
  (tool-categories  :tool/bash)     ; => #{:anomaly.category/stall ...}
  (all-tool-ids)                    ; => [:tool/agent :tool/bash ...]

  ;; Config disable then re-enable
  (-> (resolve-config [{:config/params {:t 5}}
                        {:config/directive :disable}
                        {:config/directive :enable}])
      config-enabled?)
  ;; => true

  :leave-this-here)
