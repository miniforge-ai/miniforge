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

(ns ai.miniforge.operator.mechanism
  "Intervention mechanisms that reach past the runner's control-state —
   Phase D D-3b. `application` owns the lifecycle; this namespace owns
   the mechanisms whose effect lives in another component.

   `:retry` / `:retry-from-phase` map to `workflow-resume`. The domain
   half runs here — rebuilding the run's resume state and validating a
   requested phase against its real phase history — and the runtime
   half, starting a pipeline, is a handle the process owner registers
   with `application`. A component cannot reach into a base to load a
   workflow and drive a run, so the honest split is: do the real work
   here, fail loudly when the handle is missing."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workflow-resume.interface :as wr]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Request-detail reading

(def phase-detail-keys
  "Keys `:retry-from-phase` accepts for its target phase, in priority
   order. The wire form is the string `\"phase\"`: transit-JSON map keys
   inside `:intervention/details` arrive untagged, so the reader leaves
   them as plain strings (see the golden fixture
   `contracts/operator-events/golden/retry-from-phase.transit.json`).
   The keyword form is what in-process callers write."
  ["phase" :phase])

(defn- phase-keyword
  "Coerce a details value to a phase keyword, or nil when it carries no
   usable phase. Blank strings are nil rather than `:` — an empty
   request must fail as a missing phase, not resolve to a phantom one."
  [v]
  (cond
    (keyword? v) v
    (and (string? v) (not (str/blank? v))) (keyword v)
    :else nil))

(defn requested-phase
  "The phase an intervention's `:intervention/details` targets, as a
   keyword, or nil when the request carries none."
  [interv]
  (let [details (:intervention/details interv)]
    (some #(phase-keyword (get details %)) phase-detail-keys)))

;; ── Phase history — the resume mechanism's own record of what ran ──────────

(defn known-phases
  "Every phase the target run actually recorded, drawn from the
   reconstructed resume context: phases it completed, phases it produced
   a result for (a failed phase has a result but is not completed), and
   the phase its FSM snapshot parked on. A `:retry-from-phase` request
   naming anything outside this set is rejected rather than guessed at."
  [context]
  (let [current-phase (get-in context [:machine-snapshot :execution/current-phase])]
    (cond-> (into (set (:completed-phases context))
                  (keys (:phase-results context)))
      current-phase (conj current-phase))))

(defn phases-before
  "The completed-phase prefix strictly preceding `phase`. Rewinding to
   `phase` means everything from `phase` onward must run again, so the
   resume's completed set is truncated there."
  [completed-phases phase]
  (vec (take-while #(not= phase %) completed-phases)))

;------------------------------------------------------------------------------ Layer 1
;; Resume plan

(defn resume-plan
  "The launcher payload for a retry.

   Deliberately shaped as the options a resume run needs — the same set
   `mf resume` threads into `run-pipeline` — so a launcher is a thin
   adapter rather than a translation layer.

   `from-phase` nil (plain `:retry`) keeps the FSM machine snapshot: the
   mapping table's \"resume path with FSM snapshot dispatch\". A
   `:retry-from-phase` rewind drops it, because restoring a snapshot
   parked *after* the requested phase would silently ignore the rewind."
  [interv context workflow-identity from-phase]
  (cond-> {:resume/workflow-id (str (:intervention/target-id interv))
           :resume/workflow-type (:workflow-type workflow-identity)
           :resume/workflow-version (:workflow-version workflow-identity)
           :resume/completed-phases (if from-phase
                                      (phases-before (:completed-phases context)
                                                     from-phase)
                                      (vec (:completed-phases context)))
           :resume/phase-results (:phase-results context)
           :resume/machine-snapshot (when-not from-phase
                                      (:machine-snapshot context))
           :resume/workspace (:workspace-checkpoint context)
           :resume/completed-dag-tasks (:completed-dag-tasks context)
           :resume/completed-artifacts (:completed-dag-artifacts context)
           :resume/requested-by (:intervention/requested-by interv)
           :resume/intervention-id (:intervention/id interv)}
    from-phase (assoc :resume/from-phase from-phase)))

(defn- resume-failure
  [code]
  {:failure/code code})

(defn prepare-resume
  "Rebuild resume state for a `:retry` / `:retry-from-phase`
   intervention against `events-dir`.

   Returns `{:resume/plan <plan>}`, or `{:failure/code <keyword>}` when
   the request cannot be honoured:

   - `:missing-phase`            — `:retry-from-phase` with no `phase` detail
   - `:no-resume-context`        — the target has no reconstructable history
   - `:unknown-phase`            — the requested phase never ran
   - `:unresolved-workflow-type` — no loadable workflow type in the history"
  [events-dir interv verb]
  (let [from-phase (when (= :retry-from-phase verb) (requested-phase interv))]
    (if (and (= :retry-from-phase verb) (nil? from-phase))
      (resume-failure :missing-phase)
      (let [context (wr/reconstruct-context events-dir
                                            (str (:intervention/target-id interv)))]
        (cond
          (anomaly/anomaly? context)
          (resume-failure :no-resume-context)

          (and from-phase (not (contains? (known-phases context) from-phase)))
          (resume-failure :unknown-phase)

          :else
          (let [workflow-identity (wr/resolve-workflow-identity context
                                                                (constantly nil))]
            (if (anomaly/anomaly? workflow-identity)
              (resume-failure :unresolved-workflow-type)
              {:resume/plan (resume-plan interv context workflow-identity
                                         from-phase)})))))))

(defn launched-run-id
  "The run id a launcher reported, or nil when it reported none. A
   launcher that returns an anomaly, nil, or a map without a run id has
   not dispatched anything the lifecycle can verify."
  [launch-result]
  (when (and (map? launch-result) (not (anomaly/anomaly? launch-result)))
    (:resume/run-id launch-result)))

(defn resume-observable?
  "Readback for a dispatched resume: true when the launched run has
   become observable in the event history the resume machinery itself
   reads. Asserts the mechanism's own record — a run that never started
   reconstructs to a `:not-found` anomaly and reads back false."
  [events-dir run-id]
  (boolean
   (and run-id
        (not (anomaly/anomaly? (wr/reconstruct-context events-dir (str run-id)))))))
