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
   Phase D D-3b.

   `application` owns the lifecycle; this namespace owns the two
   mechanisms whose effect lives in another component:

   | verb                        | mechanism                              |
   |-----------------------------|----------------------------------------|
   | :retry / :retry-from-phase  | `workflow-resume` reconstruction, then |
   |                             | the registered resume launcher         |
   | :re-evaluate                | a gate event that materializes a NEW   |
   |                             | PolicyEvaluation in `supervisory-state`|

   Both are split the same way: the *domain* half runs here (rebuilding
   resume state, validating the requested phase against the run's real
   phase history, turning an evaluation verdict into the gate event the
   accumulator materializes), and the *runtime* half — starting a
   pipeline, fetching a PR diff, loading policy packs — is a handle the
   process owner registers with `application`. A component cannot reach
   into a base for either, so the honest split is: do the real work
   here, fail loudly when the handle is missing.

   Nothing here fabricates a verdict. `re-evaluate` publishes only what
   the registered evaluator computed; without an evaluator the verb
   fails rather than inventing a pass."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.operator.messages :as messages]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [ai.miniforge.workflow-resume.interface :as wr]
   [clojure.set :as set]
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

;; ── Policy re-evaluation ───────────────────────────────────────────────────

(def ^:const re-evaluation-gate-id
  "Gate id stamped on the gate event a `:re-evaluate` intervention
   publishes, so the resulting PolicyEvaluation is attributable to an
   operator re-run rather than to a workflow gate."
  :supervisory/re-evaluate)

(defn evaluation-request
  "The payload handed to the registered policy evaluator. Carries the
   target and the requester identity the audit record needs; details
   pass through verbatim so a client can scope the re-run (packs,
   rule ids) without this layer growing an opinion about them."
  [interv]
  {:policy/target-type (:intervention/target-type interv)
   :policy/target-id (str (:intervention/target-id interv))
   :policy/requested-by (:intervention/requested-by interv)
   :policy/details (:intervention/details interv)
   :policy/intervention-id (:intervention/id interv)})

(defn- materialized-policy-eval-ids
  "PolicyEvaluation ids currently materialized from `stream`'s events,
   folded through the canonical supervisory accumulator. Reading the
   entity table — not the raw event log — is what makes the post-publish
   comparison a readback of the record rather than of our own write."
  [stream]
  (set (keys (:policy-evals
              (supervisory/apply-events supervisory/empty-table
                                        (es/get-events stream))))))

(defn- pack-ids
  "Pack identifiers as the strings `:policy-eval/packs-applied` requires;
   evaluators name packs with keywords or strings interchangeably."
  [packs]
  (mapv #(if (string? %) % (str (symbol %))) (or packs [])))

(defn evaluation-gate-event
  "The `:gate/passed` / `:gate/failed` event that materializes a new
   PolicyEvaluation for `interv`'s target.

   Going through a gate event rather than writing a
   `:supervisory/policy-evaluated` snapshot keeps the producer/
   materializer direction intact: producers emit gate outcomes,
   `supervisory-state` derives the record. Its `:policy-eval/id` is this
   event's `:event/id`, so every re-evaluation is a fresh, immutable
   record per N5-delta-1 §12.2 — never a mutation of a prior one."
  [stream interv evaluation]
  (let [passed? (true? (:evaluation/passed? evaluation))
        target-id (str (:intervention/target-id interv))]
    (-> (es/create-envelope stream
                            (if passed? :gate/passed :gate/failed)
                            nil
                            (messages/t :application/policy-re-evaluated
                                        {:target target-id
                                         :result (if passed? "pass" "fail")}))
        (assoc :gate/id re-evaluation-gate-id
               :gate/target-type :pr
               :gate/target-id target-id
               :gate/packs (pack-ids (:evaluation/packs-applied evaluation))
               :gate/violations (vec (:evaluation/violations evaluation))))))

(defn record-policy-evaluation!
  "Publish the evaluator's verdict and read the entity table back.

   Returns the readback the verification step asserts:
   `{:verb :re-evaluate :policy-eval/id <id> :observed <bool>
     :expected true}` — `:observed` is true only when a PolicyEvaluation
   that did not exist before the publish exists after it."
  [stream interv evaluation]
  (let [before (materialized-policy-eval-ids stream)
        event (evaluation-gate-event stream interv evaluation)
        _ (es/publish! stream event)
        added (set/difference (materialized-policy-eval-ids stream) before)]
    {:verb :re-evaluate
     :policy-eval/id (:event/id event)
     :policy-eval/passed? (true? (:evaluation/passed? evaluation))
     :observed (contains? added (:event/id event))
     :expected true}))
