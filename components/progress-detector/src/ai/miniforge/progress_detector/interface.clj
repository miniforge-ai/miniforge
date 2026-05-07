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
  "Progress-detector public API — Stage 1 framework.

   ## Architectural intent

   progress-detector is a *framework + registry mechanism*, not a source
   of truth for tool behavior or anomaly shape:

   - The canonical anomaly map lives on `ai.miniforge.anomaly.interface`.
     Detector-specific structure (`:detector/kind`, `:anomaly/class`,
     `:anomaly/severity`, `:anomaly/category`, `:anomaly/evidence`)
     goes inside the anomaly's `:anomaly/data` field per the
     `DetectorAnomalyData` schema below.
   - Tool profiles are *contributed* by the components that vendor
     each tool, via `register-tool-profile!`. Stage 1 ships an empty
     default registry; Stage 2 wires `adapter-claude-code`, `agent`,
     etc. to contribute their profiles at load time.

   ## Concepts

   An **observation** describes one tool-invocation event:
     {:tool/id :tool/Read :seq 1 :timestamp inst :tool/duration-ms 80}

   A **detector** implements the Detector protocol — a pure reducer:
     init    : config → initial-state
     observe : (detector, state, observation) → state'

   An **anomaly** is a canonical anomaly map (per anomaly/Anomaly) whose
   `:anomaly/data` carries `DetectorAnomalyData`:
     {:anomaly/type :fault
      :anomaly/message \"…\"
      :anomaly/data {:detector/kind :detector/tool-loop
                     :detector/version \"stage-1.0\"
                     :anomaly/class :mechanical
                     :anomaly/severity :error
                     :anomaly/category :anomalies.agent/tool-loop
                     :anomaly/evidence {:summary \"…\" …}}
      :anomaly/at #inst …}

   **Config layers** compose via overlay resolution:
     :inherit / :disable / :enable / :tune"
  (:require
   [ai.miniforge.anomaly.interface                    :as anomaly]
   [ai.miniforge.progress-detector.config             :as cfg]
   [ai.miniforge.progress-detector.event-envelope     :as envelope]
   [ai.miniforge.progress-detector.protocol           :as proto]
   [ai.miniforge.progress-detector.runtime            :as runtime]
   [ai.miniforge.progress-detector.schema             :as schema]
   [ai.miniforge.progress-detector.supervisor         :as sup]
   [ai.miniforge.progress-detector.tool-profile       :as tp]))

;------------------------------------------------------------------------------ Layer 0
;; Detector protocol (re-exported)

(def null-detector
  "Return a NullDetector that never flags any anomaly.
   Useful as a no-op placeholder or base for detector pipelines."
  proto/null-detector)

(def multi-detector
  "Compose multiple detectors into one fanout detector.
   Observations are forwarded to all children; anomalies are merged."
  proto/multi-detector)

(defn init
  "Produce initial detector state from config."
  [detector config]
  (proto/init detector config))

(defn observe
  "Pure reducer: fold one observation into accumulated detector state.

   Argument order is [detector state observation] — detector first
   so this works as a 2-arity reducer step via (partial observe det)
   in reduce/transduce contexts. For pipelining a single state
   forward, use `let` bindings (or `as->`) rather than `->` — `->`
   would put state in the detector slot.

   Contract (per the spec's pure-reducer requirement):
     - state argument MUST NOT be mutated
     - return value MUST be threaded back into the next observe call
     - implementations MUST NOT throw — anomalies surface as data on
       the returned state's :anomalies vector"
  [detector state observation]
  (proto/observe detector state observation))

(def reduce-observations
  "Fold a seq of observations through a detector, returning final state."
  proto/reduce-observations)

;------------------------------------------------------------------------------ Layer 1
;; Schema re-exports — anomaly validation lives on the anomaly component

(def Anomaly
  "Canonical anomaly schema (re-exported from ai.miniforge.anomaly).
   progress-detector does NOT define a parallel Anomaly schema."
  anomaly/Anomaly)

(def valid-anomaly?
  "Return true if m satisfies the canonical anomaly contract.
   Re-exported from ai.miniforge.anomaly.interface — progress-detector
   does NOT validate against a parallel schema."
  anomaly/anomaly?)

;; Progress-detector-local schemas (not duplicates of anomaly/*).
(def Observation         schema/Observation)
(def DetectorConfig      schema/DetectorConfig)
(def ToolProfile         schema/ToolProfile)
(def DetectorAnomalyData schema/DetectorAnomalyData)

;------------------------------------------------------------------------------ Layer 1
;; Event-envelope normalizer

(def make-normalizer
  "Return a per-agent-run event normalizer fn whose :seq counter
   starts at 0. Each call to make-normalizer produces an isolated
   counter so concurrent runs don't interleave seq numbers.

   Returned arities:
     (normalize event)
     (normalize event tool-profile)

   Strings, structured values, and numbers in :tool/input /
   :tool/output are replaced with a non-reversible 'hash:<hex>:len<n>'
   token — raw content never reaches detector evidence. nil is
   preserved (absence is meaningful).

   :resource/version-hash is attached only when tool-profile declares
   :determinism :stable-with-resource-version AND the event carries
   the field."
  envelope/make-normalizer)

(def valid-observation?            schema/valid-observation?)
(def explain-observation           schema/explain-observation)
(def valid-tool-profile?           schema/valid-tool-profile?)
(def explain-tool-profile          schema/explain-tool-profile)
(def valid-detector-config?        schema/valid-detector-config?)
(def explain-detector-config       schema/explain-detector-config)
(def valid-detector-anomaly-data?  schema/valid-detector-anomaly-data?)
(def explain-detector-anomaly-data schema/explain-detector-anomaly-data)

;------------------------------------------------------------------------------ Layer 2
;; Tool-profile registration (no built-in profiles — components contribute)

(def make-tool-registry
  "Create a fresh empty tool-profile registry atom. Tests should use
   this instead of the global default-registry to avoid cross-test
   leakage."
  tp/make-registry)

(def default-tool-registry
  "Process-wide tool-profile registry. Components that vendor tools
   contribute via `register-tool-profile!` at namespace-load time.
   Stage 1 ships this empty; Stage 2 wires the contributions."
  tp/default-registry)

(defn register-tool-profile!
  "Add or overwrite a tool profile in `registry-atom`.

   Throws via ex-info if the profile fails ToolProfile schema
   validation — caller bug. Returns the new registry map.

   Arguments:
     registry-atom - atom holding the registry (typically the
                     default-tool-registry)
     profile       - map satisfying ToolProfile schema:
                       :tool/id            (required, qualified keyword)
                       :determinism        (required, see schema/determinisms)
                       :anomaly/categories (optional, set of keywords)
                       :timeout-ms         (optional, int)"
  [registry-atom profile]
  (tp/register! registry-atom profile))

(defn unregister-tool-profile!
  "Remove the profile for `tool-id` from `registry-atom`.
   Returns the new registry map."
  [registry-atom tool-id]
  (tp/unregister! registry-atom tool-id))

(defn tool-lookup
  "Return the registered profile for `tool-id`, or nil.

   Arguments:
     tool-id  - qualified keyword e.g. :tool/Read
     registry - registry atom or unwrapped map (defaults to
                default-tool-registry)"
  ([tool-id]
   (tp/lookup tool-id))
  ([tool-id registry]
   (tp/lookup tool-id registry)))

(defn tool-determinism
  "Return the :determinism level for `tool-id`, or :unstable if unknown.
   :unstable is the safe default — unknown tools are treated as
   heuristic signals only, never as mechanical-eligible loops."
  ([tool-id]
   (tp/determinism-of tool-id))
  ([tool-id registry]
   (tp/determinism-of tool-id registry)))

(defn tool-categories
  "Return the anomaly category set for `tool-id`, or #{} if unknown."
  ([tool-id]
   (tp/categories-of tool-id))
  ([tool-id registry]
   (tp/categories-of tool-id registry)))

(def all-tool-ids
  "Return sorted vector of all registered tool-id keywords."
  tp/all-tool-ids)

(def validate-tool-registry
  "Per-entry ToolProfile validation report for `registry`.
   Returns: {tool-id -> {:valid? bool :errors humanized-or-nil}}."
  tp/validate-all)

;------------------------------------------------------------------------------ Layer 2
;; Config merge semantics (re-exported)

(def resolve-config
  "Resolve a seq of overlay layers into a final config map.

   Layers apply left-to-right; the base layer's :config/params seed
   the accumulator. Directives are applied to OVERLAY layers only —
   the base layer's :config/directive is recorded for audit but never
   executed (a base :disable would be self-defeating)."
  cfg/merge-config)

(def apply-config-directive
  "Apply one overlay layer onto an accumulated config map."
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
;; Supervisor re-exports

(def default-policy
  "Stage 2 class-default policy map: {:mechanical :terminate :heuristic :warn}.
   Pass as the policy arg to handle when you want no overrides."
  sup/default-policy)

(def select-controlling
  "Choose the controlling anomaly from a seq using severity→class→seq ranking.
   Re-exported from supervisor for callers that need the selection logic
   without a full handle decision."
  sup/select-controlling)

(defn handle
  "Supervisor decision over a vector of anomalies.

   Arities:

     (handle anomalies)
       Uses default-policy and no category overrides.

     (handle policy anomalies)
       Uses caller-supplied class-default policy, no category overrides.

     (handle policy on-anomaly anomalies)
       Full 3-arity form. Resolution order for action:
         1. (get on-anomaly (anomaly-category controlling))
         2. (get policy     (anomaly-class    controlling))
         3. :continue

   Returns:
     {:action     :continue | :terminate | :warn
      :anomalies  all anomalies
      :anomaly    controlling anomaly map (absent when input is empty)
      :reason     termination reason string (present only when :terminate)}"
  ([anomalies]                    (sup/handle anomalies))
  ([policy anomalies]             (sup/handle policy anomalies))
  ([policy on-anomaly anomalies]  (sup/handle policy on-anomaly anomalies)))

(def supervisor-terminate?
  "Convenience predicate over a `handle` decision map — true iff :action is :terminate."
  sup/terminate?)

;------------------------------------------------------------------------------ Layer 3
;; Runtime re-exports

(defn make-runtime
  "Build a per-agent-run detector runtime.

   Arguments:
     opts - map with:
       :detectors  - seq of Detector implementations (may be empty)
       :config     - (optional) detector config map
       :policy     - (optional) class-default supervisor policy map
       :on-anomaly - (optional) per-category action override map.
                     Consulted before :policy in supervisor/handle.
                     Example: {:anomalies.agent/tool-loop :terminate
                               :anomalies.review/stagnation :continue}

   Returns: runtime atom. Drive it with observe! / check."
  [opts]
  (runtime/make-runtime opts))

(def observe!
  "Feed one event into the runtime's detector pipeline. Returns the atom."
  runtime/observe!)

(defn check
  "Run the supervisor over unseen anomalies; mark them as seen.
   Returns a handle decision map.
   Threads both :policy and :on-anomaly through to supervisor/handle."
  [rt]
  (runtime/check rt))

(def runtime-terminate?
  "Peek at the supervisor decision without consuming the unseen window."
  runtime/terminate?)

(def current-anomalies-rt
  "Vector of every anomaly emitted so far on this runtime."
  runtime/current-anomalies)

(def new-anomalies
  "Anomalies emitted since the last check call."
  runtime/new-anomalies)

;------------------------------------------------------------------------------ Layer 3
;; Convenience constructors and helpers

(defn make-observation
  "Construct a valid observation map with required fields.

   Arguments:
     tool-id      - qualified keyword (e.g. :tool/Read)
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

   Returns: Vector of anomaly maps (possibly empty); each map conforms
   to the canonical anomaly contract with detector specifics under
   :anomaly/data."
  [state]
  (get state :anomalies []))

(defn anomalies-by-severity
  "Return anomalies from state filtered to the given severity.

   Severity reads from `[:anomaly/data :anomaly/severity]` because
   severity is detector metadata, not part of the canonical anomaly
   shape. Valid severities (per schema/severities): :info :warn
   :error :fatal.

   Arguments:
     state    - detector state map
     severity - one of #{:info :warn :error :fatal}

   Returns: Vector of matching anomaly maps."
  [state severity]
  (filterv #(= severity (get-in % [:anomaly/data :anomaly/severity]))
           (current-anomalies state)))

(defn fatal-anomalies
  "Return only severity :fatal anomalies from state."
  [state]
  (anomalies-by-severity state :fatal))

;------------------------------------------------------------------------------ Rich Comment

(comment
  ;; Smoke-test the framework
  (let [det  (null-detector)
        cfg  (resolve-config [{:config/params {:window-size 5}}
                              {:config/directive :tune
                               :config/params {:window-size 10}}])
        st0  (init det (effective-config-params cfg))
        obs1 (make-observation :tool/Read 1 (java.time.Instant/now)
                               {:tool/duration-ms 120})
        stf  (reduce-observations det (effective-config-params cfg) [obs1])]
    {:enabled?  (config-enabled? cfg)
     :params    (effective-config-params cfg)
     :anomalies (current-anomalies stf)})
  ;; => {:enabled? true :params {:window-size 10} :anomalies []}

  ;; Register a tool profile (test-style — explicit registry)
  (def reg (make-tool-registry))
  (register-tool-profile! reg
                          {:tool/id :tool/Read
                           :determinism :stable-with-resource-version
                           :anomaly/categories #{:anomalies.agent/tool-loop}})
  (tool-determinism :tool/Read reg)        ; => :stable-with-resource-version
  (tool-determinism :tool/Unknown reg)     ; => :unstable (safe default)

  :leave-this-here)
