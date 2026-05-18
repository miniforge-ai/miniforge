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

(ns ai.miniforge.automation-edge-correlator.correlator
  "Pure state machine for the AutomationEdge correlator (N5-delta-4 §2.4).

   State has the shape

       {:pending        {<trigger-event-id> <edge-map>}    ; :observed edges
        :workflow-index {<workflow-id>      <trigger-event-id>}
        :terminal       {<trigger-event-id> <edge-map>}}   ; :handled/:failed/
                                                           ; :needs-operator/
                                                           ; :suppressed

   mirroring the supervisory-state accumulator pattern: events fold over the
   state and each transition returns `[new-state edge-map-or-nil]`. Layer 1
   (`core.clj`, arrives in N15-3) wires the I/O — subscription, emission,
   clock — around this pure layer.

   ## Why three indices, not one

   The correlator must close three correctness gaps that a single `:pending`
   map cannot:

   1. **Cross-event correlation.** `:workflow/completed` / `:workflow/failed`
      carry `:workflow/id` only — they do NOT carry `:routing/trigger-event-id`
      (which lives exclusively on `:workflow/started` per N5-delta-4 §4.3).
      `:workflow-index` is the trigger→workflow bridge: populated by
      `apply-workflow-started`, consumed by the completion / failure arms.
   2. **Handler-started protection on the timeout arm.** Per §2.4 third
      transition, `:needs-operator` fires when *no handler workflow is
      observed within the suppression window*. A long-running handler that
      started promptly is NOT eligible for the timeout, regardless of its
      runtime. `expire-pending` consults `:workflow-index` to enforce this.
   3. **Replay + duplicate-trigger correctness.** Per §2.3, replay MUST NOT
      duplicate edges; per §3.6 terminal edges evict from `:pending`. Without
      `:terminal`, a duplicate trigger after terminal eviction would re-open
      an `:observed` edge with the same `:edge/id`, regressing the consumer's
      projection from `:handled` back to `:observed`. `:terminal` remembers
      the post-transition edge so a re-observation returns the same
      terminal edge byte-identically.

   ## Why `:event/timestamp` everywhere

   All Clojure event-stream envelopes (`:workflow/started`, `:workflow/completed`,
   `:workflow/failed`, the trigger events) carry their wall-clock as
   `:event/timestamp`. The correlator reads exclusively from that envelope
   key. The trigger event's `:event/timestamp` flows into the edge's
   `:edge/occurred-at` (so replay and delayed delivery preserve the original
   trigger time); each transition's `:event/timestamp` flows into the edge's
   `:edge/updated-at`. No injected `now`: the pure layer takes the timestamp
   from the event itself, and the lifecycle layer (N15-3) supplies the clock
   only for `expire-pending` where there is no triggering event.

   ## What this layer does NOT do

   - No `:edge/fallback-intervention` emission. v1 omits this field on
     `:needs-operator` edges — the per-trigger-kind intervention vocabulary
     lands with N15-6+ (operator intervention surface). The schema marks the
     field optional; emitting an out-of-vocabulary placeholder keyword would
     leave operators pointing at an action no surface can dispatch."
  (:require
   [ai.miniforge.automation-edge-correlator.schema :as schema])
  (:import
   (java.util UUID)
   (java.security MessageDigest)
   (java.nio ByteBuffer)))

;------------------------------------------------------------------------------ Layer 0
;; UUIDv5 derivation
;;
;; Inlined RFC-4122 §4.3 implementation (SHA-1, name-based). The sibling
;; `supervisory-state` component carries the same helper privately
;; (`attention.clj/uuid-v5`); we do not reach across the Polylith boundary
;; for a `defn-` and the upstream Clojure UUIDv5 library
;; (`danlentz/clj-uuid`) is not yet a workspace dependency. Lifting the
;; helper to a shared `id-derivation` component is left for a follow-on
;; (tracked alongside N15-6 interface re-exports).

(defn- uuid-v5
  "Deterministic UUIDv5 from a namespace UUID and a key string. RFC-4122
   §4.3 (SHA-1 name-based)."
  ^UUID [^UUID ns-uuid ^String k]
  (let [md     (MessageDigest/getInstance "SHA-1")
        ns-buf (ByteBuffer/wrap (byte-array 16))]
    (.putLong ns-buf (.getMostSignificantBits ns-uuid))
    (.putLong ns-buf (.getLeastSignificantBits ns-uuid))
    (.update md (.array ns-buf))
    (.update md (.getBytes k "UTF-8"))
    (let [hash    (.digest md)
          msb-raw (.getLong (ByteBuffer/wrap hash 0 8))
          lsb-raw (.getLong (ByteBuffer/wrap hash 8 8))
          ;; Clear version nibble (bits 48-51) → OR in 0x5000 to set v5.
          ;; 0xFFFFFFFFFFFF0FFF as a signed long is -61441 — written that
          ;; way because Clojure parses the literal as BigInt otherwise,
          ;; which breaks `bit-and`.
          hi      (bit-or (bit-and msb-raw (unchecked-long -61441)) 0x5000)
          ;; Clear variant bits (62-63) → set to RFC-4122 `10`. The
          ;; `Long/MIN_VALUE` constant is `0x8000000000000000`.
          lo      (bit-or (bit-and lsb-raw 0x3FFFFFFFFFFFFFFF)
                          Long/MIN_VALUE)]
      (UUID. hi lo))))

(defn edge-id-for
  "Deterministic `:edge/id` for a given `:edge/trigger-event-id`. Pure.

   Per N5-delta-4 §2.3, the namespace is the per-deployment constant in
   `schema/automation-edge-namespace` and the key is the canonical
   lowercase-hyphenated UUID-string form of `trigger-event-id`. Two calls
   with the same input MUST return `=`-equal UUIDs (the consumer
   `automation_edges` table dedups on this id)."
  ^UUID [trigger-event-id]
  (let [tid (if (instance? UUID trigger-event-id)
              trigger-event-id
              (UUID/fromString (str trigger-event-id)))]
    (uuid-v5 schema/automation-edge-namespace (str tid))))

;------------------------------------------------------------------------------ Layer 1
;; State + edge construction

(def empty-state
  "Initial correlator state — empty indices.

   `:pending`        — `:observed` edges keyed by `:edge/trigger-event-id`.
                       Evicted on transition to any terminal status.
   `:workflow-index` — `<workflow-id> → <trigger-event-id>` for handler
                       workflows whose `:workflow/started` carried a
                       `:routing/trigger-event-id`. Evicted with the edge.
   `:terminal`       — terminal edges keyed by `:edge/trigger-event-id`.
                       Retained for replay + duplicate-trigger correctness
                       per §2.3 and to support post-terminal suppression
                       per §2.4."
  {:pending        {}
   :workflow-index {}
   :terminal       {}})

(defn- new-observed-edge
  "Build the `:observed` edge map for a freshly-seen trigger. Pure.

   `:edge/occurred-at` and `:edge/updated-at` both come from the trigger
   event's own timestamp (`:trigger/occurred-at` on the classified-trigger
   map, sourced from the raw event's `:event/timestamp`)."
  [{:trigger/keys [event-id kind affected-pr-ids affected-agent-session-ids
                   occurred-at]}]
  {:edge/id                          (edge-id-for event-id)
   :edge/trigger-event-id            event-id
   :edge/trigger-kind                kind
   :edge/status                      :observed
   :edge/idempotency-key             (str event-id)
   :edge/occurred-at                 occurred-at
   :edge/updated-at                  occurred-at
   :edge/affected-pr-ids             (vec affected-pr-ids)
   :edge/affected-agent-session-ids  (vec affected-agent-session-ids)
   :edge/operator-action-required    false})

(defn apply-trigger
  "Apply a classified trigger to the correlator state.

   `classified-trigger` MUST carry:

   - `:trigger/event-id`    — the raw event's `:event/id`
   - `:trigger/kind`        — one of `schema/routing-trigger-kinds`
   - `:trigger/occurred-at` — the raw event's `:event/timestamp`

   and MAY carry:

   - `:trigger/affected-pr-ids`           (defaults to `[]`)
   - `:trigger/affected-agent-session-ids` (defaults to `[]`)

   The lifecycle layer (N15-3) assembles this map from the raw event by
   reading the envelope timestamp and applying the §3.4 affected-entity
   extraction table.

   Returns `[new-state edge]`.

   Idempotent per N5-delta-4 §2.3:

   - Re-observing a still-pending trigger returns the stored `:observed`
     edge and leaves state unchanged.
   - Re-observing a trigger whose edge has already transitioned to a
     terminal status returns the terminal edge from `:terminal` and leaves
     state unchanged. This prevents replay or duplicate delivery from
     regressing the consumer's projection back to `:observed`."
  [state classified-trigger]
  (let [event-id (:trigger/event-id classified-trigger)]
    (cond
      (contains? (:pending state) event-id)
      [state (get-in state [:pending event-id])]

      (contains? (:terminal state) event-id)
      [state (get-in state [:terminal event-id])]

      :else
      (let [edge (new-observed-edge classified-trigger)]
        [(assoc-in state [:pending event-id] edge) edge]))))

(defn apply-workflow-started
  "Index a handler workflow's `:workflow/started` event so subsequent
   `:workflow/completed` / `:workflow/failed` events can be correlated to
   their originating trigger by `:workflow/id`.

   `started` MUST carry `:workflow/id`. When it also carries
   `:routing/trigger-event-id` (per N5-delta-4 §4.3 envelope addition), the
   correlator records the mapping `workflow-id → trigger-event-id` in
   `:workflow-index`. Workflows started outside the routing path (operator-
   initiated runs, etc.) MUST omit the field and are not indexed.

   Returns `[new-state nil]` — `apply-workflow-started` never emits an
   edge upsert; the `:observed` edge was already emitted by `apply-trigger`
   when the trigger landed.

   Tolerates out-of-order delivery: if the trigger event has not yet been
   observed when its handler's `:workflow/started` lands, the mapping is
   recorded anyway. A later `apply-trigger` opens the `:observed` edge with
   handler-started protection already in place — `expire-pending` will not
   transition it to `:needs-operator` on the timeout arm."
  [state {:workflow/keys [id] :routing/keys [trigger-event-id]}]
  (if (and id trigger-event-id)
    [(assoc-in state [:workflow-index id] trigger-event-id) nil]
    [state nil]))

(defn- mark-handled
  [edge workflow-id event-timestamp]
  (assoc edge
         :edge/status                     :handled
         :edge/handled-by-workflow-run-id workflow-id
         :edge/operator-action-required   false
         :edge/updated-at                 event-timestamp))

(defn- mark-failed
  [edge workflow-id event-timestamp]
  (assoc edge
         :edge/status                     :failed
         :edge/handled-by-workflow-run-id workflow-id
         :edge/operator-action-required   true
         :edge/updated-at                 event-timestamp))

(defn- mark-suppressed
  [edge event-timestamp]
  (assoc edge
         :edge/status                   :suppressed
         :edge/operator-action-required false
         :edge/updated-at               event-timestamp))

(defn- mark-needs-operator
  "Transition to `:needs-operator`. `:edge/fallback-intervention` is
   deliberately omitted — v1 does not emit a placeholder keyword (see the
   ns docstring); N15-6+ wires the per-trigger-kind intervention
   vocabulary."
  [edge event-timestamp]
  (assoc edge
         :edge/status                   :needs-operator
         :edge/operator-action-required true
         :edge/updated-at               event-timestamp))

(defn- transition-and-evict
  "Move `trigger-event-id`'s pending edge to `:terminal` after applying
   `transition-fn`, evicting it from `:pending` (and from `:workflow-index`
   if a handler workflow had been indexed against it). Pure.

   Returns `[new-state transitioned-edge]`. Returns `[state nil]` if no
   pending edge exists for `trigger-event-id`."
  [state trigger-event-id transition-fn]
  (if-let [pending (get-in state [:pending trigger-event-id])]
    (let [transitioned (transition-fn pending)
          ;; Find any workflow-id pointing at this trigger; evict that
          ;; entry too. (For the handled/failed paths the workflow-id is
          ;; known up-front; for the no-handler / suppress paths the
          ;; trigger was opened but no handler was indexed — the scan is
          ;; a no-op then.)
          state'       (->> (:workflow-index state)
                            (remove (fn [[_ tid]] (= tid trigger-event-id)))
                            (into {})
                            (assoc state :workflow-index))]
      [(-> state'
           (update :pending dissoc trigger-event-id)
           (assoc-in [:terminal trigger-event-id] transitioned))
       transitioned])
    [state nil]))

;------------------------------------------------------------------------------ Layer 2
;; Public transitions — one per §2.4 state-machine arrow

(defn apply-workflow-completed
  "Transition an indexed handler workflow's pending edge to `:handled`.

   `completion` MUST carry `:workflow/id` and `:event/timestamp` (the
   envelope key — `:workflow/completed` events do NOT carry
   `:routing/trigger-event-id`; the correlator looks the trigger up via
   `:workflow-index` populated by `apply-workflow-started`).

   Returns `[new-state edge-or-nil]`. When the workflow-id has no indexed
   trigger-event-id (workflow started outside routing, or out-of-order
   delivery where the started event was lost), the state is unchanged and
   the edge is nil per §3.5."
  [state {:workflow/keys [id] :event/keys [timestamp]}]
  (if-let [trigger-event-id (get-in state [:workflow-index id])]
    (transition-and-evict state trigger-event-id
                          #(mark-handled % id timestamp))
    [state nil]))

(defn apply-workflow-failed
  "Transition an indexed handler workflow's pending edge to `:failed`.

   Same contract as `apply-workflow-completed` but transitions to `:failed`
   and sets `:edge/operator-action-required true`."
  [state {:workflow/keys [id] :event/keys [timestamp]}]
  (if-let [trigger-event-id (get-in state [:workflow-index id])]
    (transition-and-evict state trigger-event-id
                          #(mark-failed % id timestamp))
    [state nil]))

(defn apply-no-handler
  "Transition a pending edge to `:needs-operator` when a handler explicitly
   declares `:no-handler-available` for its trigger (§2.4 third transition,
   no-handler arm — `resume-dispatcher` emits this when the routing edge
   has no configured workflow).

   `signal` MUST carry `:routing/trigger-event-id` and `:event/timestamp`.
   Returns `[new-state edge-or-nil]`; no-op when no pending edge matches."
  [state {:routing/keys [trigger-event-id] :event/keys [timestamp]}]
  (transition-and-evict state trigger-event-id
                        #(mark-needs-operator % timestamp)))

(defn apply-suppress
  "Transition an edge to `:suppressed` on an `:edge/suppress` operator
   intervention (§2.4 fourth transition).

   `intervention` MUST carry `:edge/trigger-event-id` and `:event/timestamp`.
   Per §2.4 the operator MAY suppress an edge in any non-terminal *or*
   terminal state — the suppression replaces the prior status while
   preserving `:edge/handled-by-workflow-run-id` if it was set.

   Behavior:

   - Pending edge → `:suppressed`, evicted from `:pending`, moved to
     `:terminal`.
   - Already-terminal edge → re-emitted as `:suppressed`, replacing the
     prior terminal entry. `:edge/handled-by-workflow-run-id` is preserved
     from whichever terminal state it was previously in.
   - No record at all → `[state nil]`.

   Returns `[new-state edge-or-nil]`."
  [state {:edge/keys [trigger-event-id] :event/keys [timestamp]}]
  (cond
    (contains? (:pending state) trigger-event-id)
    (transition-and-evict state trigger-event-id
                          #(mark-suppressed % timestamp))

    (contains? (:terminal state) trigger-event-id)
    (let [prior        (get-in state [:terminal trigger-event-id])
          transitioned (mark-suppressed prior timestamp)]
      [(assoc-in state [:terminal trigger-event-id] transitioned) transitioned])

    :else
    [state nil]))

(defn- handler-started?
  "True when any entry in `:workflow-index` points at `trigger-event-id` —
   i.e. a `:workflow/started` carrying that trigger has been observed."
  [state trigger-event-id]
  (some (fn [[_ tid]] (= tid trigger-event-id))
        (:workflow-index state)))

(defn- expired?
  "True when the time between `:edge/occurred-at` and `now` exceeds
   `suppression-window-ms`."
  [edge now suppression-window-ms]
  (let [occurred (:edge/occurred-at edge)
        elapsed  (- (inst-ms now) (inst-ms occurred))]
    (> elapsed suppression-window-ms)))

(defn expire-pending
  "Scan the pending-edge index and transition any edge whose age exceeds
   `suppression-window-ms` AND whose trigger has not been seen by any
   `:workflow/started` to `:needs-operator` (§2.4 third transition, timeout
   arm — *no handler workflow observed within the suppression window*).

   Edges whose handler workflow HAS been observed (i.e. `:workflow-index`
   carries an entry pointing at the trigger-event-id) are NOT eligible for
   timeout regardless of their pending age — a long-running handler that
   started promptly stays pending until it completes or fails.

   Returns `[new-state expired-edges]` where `expired-edges` is the vector
   of transitioned edge maps the emitter (N15-3) will publish.

   Pure: `now` is injected, no system clock."
  [state now suppression-window-ms]
  (let [pending  (:pending state)
        expired  (into []
                       (comp (filter (fn [[_ edge]]
                                       (expired? edge now suppression-window-ms)))
                             (remove (fn [[tid _]] (handler-started? state tid)))
                             (map (fn [[_ edge]] (mark-needs-operator edge now))))
                       pending)
        evict    (into #{} (map :edge/trigger-event-id) expired)
        pending' (into {}
                       (remove (fn [[event-id _]] (contains? evict event-id)))
                       pending)
        ;; Move evicted edges into :terminal so duplicate observations
        ;; after expiry do not regress the consumer's projection.
        terminal' (reduce (fn [t edge]
                            (assoc t (:edge/trigger-event-id edge) edge))
                          (:terminal state)
                          expired)]
    [(assoc state :pending pending' :terminal terminal') expired]))

(comment
  ;; REPL — observe → handler starts → handler completes
  (let [tid     (random-uuid)
        wf-id   (random-uuid)
        ts1     (java.util.Date.)
        ts2     (java.util.Date.)
        ts3     (java.util.Date.)
        [s1 e1] (apply-trigger empty-state
                               {:trigger/event-id                  tid
                                :trigger/kind                      :pr-merged
                                :trigger/occurred-at               ts1
                                :trigger/affected-pr-ids           [["miniforge-ai/miniforge" 999]]
                                :trigger/affected-agent-session-ids []})
        [s2 _]  (apply-workflow-started s1 {:workflow/id              wf-id
                                            :routing/trigger-event-id tid
                                            :event/timestamp          ts2})
        [s3 e3] (apply-workflow-completed s2 {:workflow/id     wf-id
                                              :event/timestamp ts3})]
    {:opened   e1
     :handled  e3
     :pending  (:pending s3)
     :terminal (:terminal s3)}))
