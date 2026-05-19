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

(ns ai.miniforge.automation-edge-correlator.core
  "Lifecycle wrapper for the AutomationEdge correlator.

   Wraps the pure state machine in `correlator.clj` with I/O:

   - Subscribes to the event stream, filtering `:supervisory/automation-
     edge-upserted` out of its own input (recursion prevention per
     N5-delta-4 §3.3).
   - Classifies trigger events via `triggers/classify-trigger`, assembles
     the §3.4 payload-derived `classified-trigger`, and folds it through
     `correlator/apply-trigger`.
   - Dispatches non-trigger events (`:workflow/started`,
     `:workflow/completed`, `:workflow/failed`, `:edge/suppress`,
     `:routing/no-handler`) to the matching pure transition.
   - Emits a `:supervisory/automation-edge-upserted` envelope on the
     stream whenever a transition returns a non-nil edge.
   - On `start!`, replays the event-stream prefix to reconstruct state +
     re-emit upserts (§3.6 cross-restart).
   - Runs a periodic expire-pending tick on a `ScheduledExecutorService`
     so `:observed` edges past the suppression window transition to
     `:needs-operator` (§2.4 third transition, timeout arm).

   Design choices recorded for review:

   - `ScheduledExecutorService` rather than `core.async`: `supervisory-
     state` carries no periodic ticker precedent, and `core.async` would
     introduce a new dependency surface for what is fundamentally one
     thread firing every 30 s. The executor is shut down deterministically
     in `stop!`.
   - State is an atom holding `{:correlator <pure-state>, :stream,
     :subscribed?, :scheduler, :config, :clock}`. The pure-state lives
     under a nested key so a single atomic swap can both apply a
     transition and clear the subscription latch on shutdown.
   - The expire tick reads the clock injected via `deps`. Tests inject a
     fixed-time fn and drive the tick by hand via `expire-once!`."
  (:require
   [ai.miniforge.automation-edge-correlator.correlator :as correlator]
   [ai.miniforge.automation-edge-correlator.emitter :as emitter]
   [ai.miniforge.automation-edge-correlator.triggers :as triggers]
   [ai.miniforge.event-stream.interface :as es])
  (:import
   (java.util.concurrent Executors
                         ScheduledExecutorService
                         TimeUnit)))

;------------------------------------------------------------------------------ Layer 0
;; Subscriber id + defaults

(def ^:const subscriber-id ::automation-edge-correlator)

(def ^:private default-config
  "Defaults sourced from N5-delta-4 §6.

   `:correlator/correlation-window-ms` is unused in v1 — included for
   forward compatibility with the §3.5 case 2 heuristic-fallback path
   that lands in a follow-on. The brief explicitly defers it."
  {:correlator/suppression-window-ms 300000   ; 5 min — §6 default
   :correlator/correlation-window-ms 60000    ; 60 s  — §6 default (v1 unused)
   :correlator/expire-tick-ms        30000})  ; 30 s  — see expire-cadence note

;; Expire-tick cadence rationale: the 30-s default is one tenth of the
;; default 5-minute suppression window. That cadence makes worst-case
;; timeout latency (suppression-window + one tick) at most 5m30s — well
;; within the operator-attention budget — without spinning the scheduler
;; pointlessly. Operators MAY override via `:correlator/expire-tick-ms`
;; on the `deps` config map; tests drive the tick by hand via
;; `expire-once!` and set the cadence to a no-op-large value.

(def ^:private self-emitted-type
  "The single event type this correlator emits. Filtered out of its own
   input per §3.3."
  :supervisory/automation-edge-upserted)

;------------------------------------------------------------------------------ Layer 1
;; Classified-trigger assembly — lifts the §3.4 payload-derivation table

(defn- one-pr-tuple
  "Single `[repo number]` tuple from `:pr/repo` + `:pr/number`, or `[]`
   when either is absent. Per §3.4 every PR-bearing trigger uses this
   shape."
  [{:pr/keys [repo number]}]
  (if (and repo number) [[repo number]] []))

(defn- ->session-vec
  "Wrap a single optional session-id into a vector, omitting nil. §3.4
   uses `[<id>]` when present, `[]` otherwise."
  [maybe-id]
  (if maybe-id [maybe-id] []))

(defn- payload-pr-ids
  "Lift `:edge/affected-pr-ids` from a raw event per §3.4. The shape is
   the same `[[repo number]]` tuple form across every PR-bearing trigger
   kind. Returns `[]` when the payload does not carry both fields (the
   correlator MUST emit the edge anyway per §3.4 last paragraph — empty
   vectors are the documented contract)."
  [_kind event]
  (one-pr-tuple event))

(defn- payload-agent-ids
  "Lift `:edge/affected-agent-session-ids` from a raw event per §3.4.
   Per-kind: only `:review-comments-arrived` carries
   `:comments/agent-session-id`, and only `:standards-review-arrived`
   carries `:affected/workflow-run-id` (which §3.4 says MAY be mapped
   through a workflow→agent index when one is available; v1 records the
   workflow-run-id verbatim and leaves the mapping to consumers — the
   field is open). All others derive `[]`."
  [kind event]
  (case kind
    :review-comments-arrived
    (->session-vec (:comments/agent-session-id event))

    :standards-review-arrived
    (->session-vec (:affected/workflow-run-id event))

    []))

(defn assemble-classified-trigger
  "Lift a raw event + its classified `kind` into the map the pure layer
   consumes (`correlator/apply-trigger`).

   Pure. The lifecycle layer calls this only after
   `triggers/classify-trigger` has confirmed the event maps to a
   RoutingTriggerKind.

   Per N5-delta-4 §3.4:

   - `:trigger/event-id`    ← raw `:event/id`
   - `:trigger/kind`        ← classified kind
   - `:trigger/occurred-at` ← raw `:event/timestamp`
   - `:trigger/affected-pr-ids`           ← `[[:pr/repo :pr/number]]` (one tuple)
                                            when both fields present, else `[]`.
   - `:trigger/affected-agent-session-ids`← per-kind: see
                                            `payload-agent-ids`."
  [kind event]
  {:trigger/event-id                   (:event/id event)
   :trigger/kind                       kind
   :trigger/occurred-at                (:event/timestamp event)
   :trigger/affected-pr-ids            (payload-pr-ids kind event)
   :trigger/affected-agent-session-ids (payload-agent-ids kind event)})

;------------------------------------------------------------------------------ Layer 2
;; Per-event dispatch

(defn- emit-upsert!
  "Build an upsert envelope via `emitter/upsert-event` and publish it on
   the stream.

   The base envelope is built via `es/create-envelope` so the
   correlator inherits the canonical `:event/id` (Snowflake-encoded
   when the stream is so configured), `:event/version`,
   `:event/sequence-number`, and any identity-propagation fields the
   stream attaches. The pure emitter then overrides only the edge-
   specific fields (`:event/timestamp` from `:edge/updated-at`,
   `:message`, `:supervisory/entity`).

   `:workflow/id` is threaded through `create-envelope`'s positional
   `workflow-id` arg when the edge has been correlated to a handler
   (`:edge/handled-by-workflow-run-id` is non-nil). For pre-handler
   edges (`:observed` with no workflow yet, or triggers like
   `:pr/merged` that name no workflow) the value is `nil` and the
   envelope's `:workflow/id` is left absent. Downstream filters use
   the threaded value for workflow-scoped views."
  [stream edge]
  (let [workflow-id  (:edge/handled-by-workflow-run-id edge)
        base         (es/create-envelope stream
                                         self-emitted-type
                                         workflow-id
                                         "")
        event        (emitter/upsert-event base edge)]
    (es/publish! stream event)
    event))

(defn- handle-trigger
  "Classify a raw event; if it's a trigger, fold it through the pure
   layer and emit the resulting edge."
  [pure-state stream event]
  (if-let [kind (triggers/classify-trigger
                 ;; The classify table is keyed on `:type`; the envelope
                 ;; ships `:event/type`. Adapt here so both wire shapes
                 ;; route correctly — keeps the pure classifier untouched.
                 (assoc event :type (:event/type event)))]
    (let [classified  (assemble-classified-trigger kind event)
          [next-state edge] (correlator/apply-trigger pure-state classified)]
      (when edge (emit-upsert! stream edge))
      next-state)
    pure-state))

(defn- handle-workflow-started
  [pure-state event]
  (let [[next-state _] (correlator/apply-workflow-started pure-state event)]
    next-state))

(defn- handle-workflow-completed
  "`:workflow/completed` plays two roles per §3.4 / §2.4: it's both a
   handler signal (transition any indexed pending edge to `:handled`)
   AND a routing trigger itself (opens a new `:workflow-completed`
   edge). Apply both in order — handler first so a re-observation of
   the same id falls into the §2.3 terminal branch."
  [pure-state stream event]
  (let [[after-completion handled-edge]
        (correlator/apply-workflow-completed pure-state event)]
    (when handled-edge (emit-upsert! stream handled-edge))
    (handle-trigger after-completion stream event)))

(defn- handle-workflow-failed
  [pure-state stream event]
  (let [[next-state edge] (correlator/apply-workflow-failed pure-state event)]
    (when edge (emit-upsert! stream edge))
    next-state))

(defn- handle-no-handler
  [pure-state stream event]
  (let [[next-state edge] (correlator/apply-no-handler pure-state event)]
    (when edge (emit-upsert! stream edge))
    next-state))

(defn- handle-suppress
  [pure-state stream event]
  (let [[next-state edge] (correlator/apply-suppress pure-state event)]
    (when edge (emit-upsert! stream edge))
    next-state))

(defn- tick
  "Apply one event to the correlator's pure state. Emits any resulting
   edges on the stream. Returns the new pure state."
  [pure-state stream event]
  (let [event-type (:event/type event)]
    (cond
      ;; §3.3 recursion prevention. The subscription filter ALSO drops
      ;; these, but we re-check here so direct callers (and the replay
      ;; path) cannot accidentally fold a self-emission.
      (= event-type self-emitted-type)
      pure-state

      (= event-type :workflow/started)
      (handle-workflow-started pure-state event)

      (= event-type :workflow/failed)
      (handle-workflow-failed pure-state stream event)

      (= event-type :edge/suppress)
      (handle-suppress pure-state stream event)

      (= event-type :routing/no-handler)
      (handle-no-handler pure-state stream event)

      ;; `:workflow/completed` is both a handler signal AND a routing
      ;; trigger per §3.4. The dispatch handles both arms.
      (= event-type :workflow/completed)
      (handle-workflow-completed pure-state stream event)

      ;; Every other classifiable event runs the trigger arm only.
      :else
      (handle-trigger pure-state stream event))))

;------------------------------------------------------------------------------ Layer 2
;; Lifecycle handle

(defn- not-self-emit?
  "Subscription-level filter — drops our own upsert events at the
   subscriber boundary per §3.3."
  [event]
  (not= self-emitted-type (:event/type event)))

(defn create
  "Build a fresh correlator handle bound to `stream`. Does not subscribe
   yet — call `start!` to begin consuming events.

   `deps` MAY carry:

   - `:clock`  — 0-arity fn returning a `java.util.Date`. Defaults to
                 `(java.util.Date.)`. The pure state machine reads the
                 timestamp from the event itself for trigger / workflow
                 transitions, so the clock is consulted only by
                 `expire-once!` (which has no triggering event).
   - `:config` — map of `:correlator/*` overrides; see `default-config`."
  [stream & [{:keys [clock config]}]]
  (atom {:correlator  correlator/empty-state
         :stream      stream
         :subscribed? false
         :scheduler   nil
         :clock       (or clock #(java.util.Date.))
         :config      (merge default-config config)}))

(defn- handle-event!
  [handle event]
  (when (not-self-emit? event)
    (swap! handle update :correlator tick (:stream @handle) event)))

(defn- replay!
  "Fold the event-stream's already-published prefix through the
   correlator + re-emit upserts (§3.6 cross-restart).

   `get-events` is captured at start-of-replay; concurrent publishes
   during the fold land via the subscriber after the snapshot, so
   ordering is preserved with no duplicate-application."
  [handle]
  (let [{:keys [stream]} @handle
        snapshot (es/get-events stream)]
    (doseq [event snapshot]
      (when (not-self-emit? event)
        (swap! handle update :correlator tick stream event)))))

(defn expire-once!
  "Drive one expire-pending pass. Used by the scheduled tick AND by
   tests that need deterministic time advancement (without
   `Thread/sleep`).

   Emits a `:supervisory/automation-edge-upserted` for every edge that
   transitions to `:needs-operator`. Idempotent: a second call with no
   new clock advance produces no further emissions."
  [handle]
  (let [{:keys [clock config stream]} @handle
        suppression-window-ms (:correlator/suppression-window-ms config)
        now ((or clock #(java.util.Date.)))
        ;; Pull-and-swap the new state atomically; collect the expired
        ;; edges from the returned tuple so emission happens outside the
        ;; swap (publish! must not run inside a `swap!` body — it can
        ;; recur into other subscribers).
        new-edges
        (let [edges-ref (atom nil)]
          (swap! handle
                 (fn [{:keys [correlator] :as state}]
                   (let [[next-pure expired]
                         (correlator/expire-pending correlator now suppression-window-ms)]
                     (reset! edges-ref expired)
                     (assoc state :correlator next-pure))))
          @edges-ref)]
    (doseq [edge new-edges]
      (emit-upsert! stream edge))
    new-edges))

(defn- start-scheduler!
  "Spin up a single-thread `ScheduledExecutorService` that fires
   `expire-once!` every `expire-tick-ms`. The thread is a daemon so JVM
   shutdown is unaffected if `stop!` is missed (defensive — the
   lifecycle contract is that callers `stop!` explicitly)."
  ^ScheduledExecutorService [handle expire-tick-ms]
  (let [scheduler (Executors/newSingleThreadScheduledExecutor
                   (reify java.util.concurrent.ThreadFactory
                     (newThread [_ r]
                       (doto (Thread. ^Runnable r "automation-edge-correlator-expire-tick")
                         (.setDaemon true)))))]
    (.scheduleAtFixedRate scheduler
                          ^Runnable (fn []
                                      (try
                                        (expire-once! handle)
                                        (catch Throwable t
                                          ;; A single tick failure MUST
                                          ;; NOT kill the scheduler — a
                                          ;; thrown Throwable from a
                                          ;; `scheduleAtFixedRate` task
                                          ;; cancels all future runs
                                          ;; silently per
                                          ;; `ScheduledExecutorService`
                                          ;; contract.  We catch, log to
                                          ;; stderr (matching the
                                          ;; sibling event-stream sink
                                          ;; failure convention), and
                                          ;; let the next tick try
                                          ;; again. Operators tail
                                          ;; stderr in dev; in
                                          ;; production the journal
                                          ;; captures it and an alert
                                          ;; can scrape on the prefix.
                                          (binding [*out* *err*]
                                            (println
                                             (str "WARNING: automation-edge-correlator "
                                                  "expire-tick failed: "
                                                  (.getMessage t))))
                                          nil)))
                          (long expire-tick-ms)
                          (long expire-tick-ms)
                          TimeUnit/MILLISECONDS)
    scheduler))

(defn start!
  "Subscribe to the stream, replay the prefix, drain anything that
   landed during replay, then start the expire ticker. Idempotent — a
   second call on a started handle is a no-op.

   ## Race-free replay (Copilot review on PR #908)

   The naïve order replay-then-subscribe loses any event published in
   the gap between `get-events` snapshot and `subscribe!` registration.
   Production deployments wire a hot stream where that gap can be
   non-empty.

   The barrier: subscribe FIRST with a buffering callback that
   appends to an in-memory queue. Then replay. Then drain the queue
   through the real handler. Then flip a `replaying?` flag and drain
   ONE MORE TIME to catch anything that landed between the last drain
   and the flag flip. After the second drain the buffering callback
   sees `replaying? = false` and goes direct.

   Overlap is fine: replay-folded events and any buffered events that
   the replay snapshot already contained will be applied through the
   pure layer twice — but the state machine is idempotent on
   re-observation (§2.3 terminal memory; `:pending` membership check)
   so byte-identical edges flow out either way."
  [handle]
  (let [{:keys [stream subscribed? config]} @handle]
    (when-not subscribed?
      (swap! handle assoc :subscribed? true)
      (let [buffer     (java.util.concurrent.LinkedBlockingQueue.)
            replaying? (atom true)
            ;; Buffering subscriber: while replaying, enqueue; after,
            ;; go direct. The flag flip below is the cutover.
            handler    (fn [event]
                         (if @replaying?
                           (.add buffer event)
                           (handle-event! handle event)))]
        (es/subscribe! stream subscriber-id handler not-self-emit?)
        ;; Replay reads `get-events` as a snapshot. Events that landed
        ;; before the snapshot are folded here; events that landed
        ;; after (i.e. concurrent publishers) are in `buffer`.
        (replay! handle)
        ;; Drain everything that arrived during replay. Loop until the
        ;; queue stays empty across one full pass — concurrent
        ;; publishers might add while we're draining.
        (loop []
          (let [batch (java.util.ArrayList.)]
            (.drainTo buffer batch)
            (when (pos? (.size batch))
              (doseq [event batch] (handle-event! handle event))
              (recur))))
        ;; Flip the flag, then drain one more time. Any event that
        ;; landed BEFORE the flip went into the buffer (drained here);
        ;; anything AFTER goes through the direct path via the
        ;; subscriber callback.
        (reset! replaying? false)
        (let [final-batch (java.util.ArrayList.)]
          (.drainTo buffer final-batch)
          (doseq [event final-batch] (handle-event! handle event))))
      (let [scheduler (start-scheduler! handle (:correlator/expire-tick-ms config))]
        (swap! handle assoc :scheduler scheduler)))
    handle))

(defn stop!
  "Unsubscribe from the stream and cancel the expire ticker. Idempotent.
   Retains the in-memory pure state so post-shutdown queries can still
   read the edge indices."
  [handle]
  (let [{:keys [stream subscribed? ^ScheduledExecutorService scheduler]} @handle]
    (when subscribed?
      (es/unsubscribe! stream subscriber-id)
      (when scheduler
        (.shutdownNow scheduler))
      (swap! handle assoc :subscribed? false :scheduler nil))
    handle))

(defn attach!
  "Convenience: `create` + `start!` in one step. Test injection point
   for an in-memory event stream — equivalent to `start!` on a fresh
   handle, mirroring the supervisory-state convention."
  ([stream] (attach! stream nil))
  ([stream deps]
   (start! (create stream deps))))

(defn attached?
  "True when the correlator is subscribed to `stream`."
  [stream]
  (contains? (:subscribers @stream) subscriber-id))

;------------------------------------------------------------------------------ Layer 3
;; Read-only query API — useful for tests and operator surfaces

(defn pure-state
  "Snapshot of the correlator's pure state (read-only)."
  [handle]
  (:correlator @handle))

(comment
  ;; REPL — drive the lifecycle against an in-memory stream.
  (require '[ai.miniforge.event-stream.core :as es-core])
  (let [stream (es-core/create-event-stream {:sinks []})
        deps   {:clock #(java.util.Date.)}
        handle (attach! stream deps)]
    (es/publish! stream {:event/type      :pr/merged
                         :event/id        (random-uuid)
                         :event/timestamp (java.util.Date.)
                         :event/version   "1.0.0"
                         :event/sequence-number 0
                         :pr/repo         "miniforge-ai/miniforge"
                         :pr/number       999
                         :message         "merged"})
    (Thread/sleep 20)
    (let [edges (filter #(= :supervisory/automation-edge-upserted (:event/type %))
                        (es/get-events stream))]
      (stop! handle)
      edges))
  :leave-this-here)
