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

       {:pending {<trigger-event-id> <edge-map>}}

   mirroring the supervisory-state accumulator pattern: events fold over the
   state and each transition returns `[new-state edge-map-or-nil]`. Layer 1
   (`core.clj`, arrives in N15-3) wires the I/O — subscription, emission,
   clock — around this pure layer.

   Per the spec, terminal statuses (`:handled`, `:failed`, `:suppressed`,
   `:needs-operator`) MUST evict from `:pending`. `:needs-operator` is
   terminal for the in-memory index per §3.6 bullet 1 — operators reanimate
   it through the intervention surface (N15-6+), not by holding the entry in
   the pending map.

   No I/O. No system clock. All transitions receive `now` (a `java.util.Date`
   or `inst?`) as an injected parameter."
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
  "Initial correlator state — empty pending-edge index."
  {:pending {}})

(defn- new-observed-edge
  "Build the `:observed` edge map for a freshly-seen trigger. Pure."
  [{:trigger/keys [event-id kind affected-pr-ids affected-agent-session-ids]} now]
  {:edge/id                          (edge-id-for event-id)
   :edge/trigger-event-id            event-id
   :edge/trigger-kind                kind
   :edge/status                      :observed
   :edge/idempotency-key             (str event-id)
   :edge/occurred-at                 now
   :edge/updated-at                  now
   :edge/affected-pr-ids             (vec affected-pr-ids)
   :edge/affected-agent-session-ids  (vec affected-agent-session-ids)
   :edge/operator-action-required    false})

(defn apply-trigger
  "Apply a classified trigger to the correlator state.

   `classified-trigger` MUST carry `:trigger/event-id`, `:trigger/kind`, and
   MAY carry `:trigger/affected-pr-ids` / `:trigger/affected-agent-session-ids`
   (defaults to empty vectors). `now` is the wall-clock instant injected by
   the caller — used for both `:edge/occurred-at` and `:edge/updated-at` on
   the new edge.

   Returns `[new-state edge]`.

   Idempotent per N5-delta-4 §2.3: re-observing the same `:trigger/event-id`
   returns the previously-stored edge byte-identically and leaves the
   pending map unchanged."
  [state classified-trigger now]
  (let [event-id (:trigger/event-id classified-trigger)
        existing (get-in state [:pending event-id])]
    (if existing
      [state existing]
      (let [edge (new-observed-edge classified-trigger now)]
        [(assoc-in state [:pending event-id] edge) edge]))))

(defn- transition-pending
  "Look up an edge by `trigger-event-id`, transition it via `transition-fn`
   if found, evict from `:pending`, and return `[new-state edge]`.

   Returns `[state nil]` when no pending edge matches the id — the
   correlator does not invent correlations per §3.5."
  [state trigger-event-id transition-fn]
  (if-let [pending (get-in state [:pending trigger-event-id])]
    (let [transitioned (transition-fn pending)]
      [(update state :pending dissoc trigger-event-id) transitioned])
    [state nil]))

(defn- mark-handled
  [edge workflow-id now]
  (assoc edge
         :edge/status                     :handled
         :edge/handled-by-workflow-run-id workflow-id
         :edge/operator-action-required   false
         :edge/updated-at                 now))

(defn- mark-failed
  [edge workflow-id now]
  (assoc edge
         :edge/status                     :failed
         :edge/handled-by-workflow-run-id workflow-id
         :edge/operator-action-required   true
         :edge/updated-at                 now))

(defn- mark-suppressed
  [edge now]
  (assoc edge
         :edge/status                   :suppressed
         :edge/operator-action-required false
         :edge/updated-at               now))

(def ^:private manual-disposition-intervention
  "Placeholder fallback intervention keyword used on `:needs-operator`
   transitions in v1. The per-kind intervention vocabulary lands with
   N15-6+ (operator intervention surface); v1 emits one shared placeholder."
  :edge/manual-disposition-required)

(defn- mark-needs-operator
  [edge now]
  (assoc edge
         :edge/status                   :needs-operator
         :edge/operator-action-required true
         :edge/fallback-intervention    manual-disposition-intervention
         :edge/updated-at               now))

;------------------------------------------------------------------------------ Layer 2
;; Public transitions — one per §2.4 state-machine arrow

(defn apply-workflow-completed
  "Transition a pending edge to `:handled` when a `:workflow/completed`
   event correlates by `:routing/trigger-event-id` (§2.4 first transition).

   `completion` MUST carry `:routing/trigger-event-id` and `:workflow/id`,
   and SHOULD carry `:workflow/timestamp` (the `:edge/updated-at` value).
   Returns `[new-state edge-or-nil]`. When no pending edge matches the
   trigger-event-id, the state is unchanged and the edge is nil — the
   workflow is treated as an unrelated completion per §3.5."
  [state {:keys [routing/trigger-event-id workflow/id workflow/timestamp]}]
  (transition-pending state trigger-event-id
                      #(mark-handled % id timestamp)))

(defn apply-workflow-failed
  "Transition a pending edge to `:failed` when a `:workflow/failed` event
   correlates by `:routing/trigger-event-id` (§2.4 second transition).

   Sets `:edge/operator-action-required true`. Same contract as
   `apply-workflow-completed` otherwise — returns `[new-state edge-or-nil]`,
   no-op when no match."
  [state {:keys [routing/trigger-event-id workflow/id workflow/timestamp]}]
  (transition-pending state trigger-event-id
                      #(mark-failed % id timestamp)))

(defn apply-no-handler
  "Transition a pending edge to `:needs-operator` when a handler explicitly
   declares `:no-handler-available` for its trigger (§2.4 third transition,
   no-handler arm).

   `signal` MUST carry `:routing/trigger-event-id` and SHOULD carry
   `:timestamp`. Returns `[new-state edge-or-nil]`. No-op when no match."
  [state {:keys [routing/trigger-event-id timestamp]}]
  (transition-pending state trigger-event-id
                      #(mark-needs-operator % timestamp)))

(defn apply-suppress
  "Transition a pending edge to `:suppressed` on an `:edge/suppress`
   intervention (§2.4 fourth transition).

   `intervention` MUST carry `:edge/trigger-event-id` and SHOULD carry
   `:timestamp`. Preserves `:edge/handled-by-workflow-run-id` if it was set
   on a prior transition (per §2.4 — the spec contemplates suppression of
   already-handled edges via operator intervention). Returns
   `[new-state edge-or-nil]`. No-op when no match.

   NOTE: in-memory state only ever holds `:observed` edges (terminal
   statuses evict on transition). Suppressing a fully-terminal edge after
   eviction is therefore out of scope for this pure layer — it is handled
   by the operator intervention surface (N15-6+) operating directly on the
   consumer's entity table."
  [state {:keys [edge/trigger-event-id timestamp]}]
  (transition-pending state trigger-event-id
                      #(mark-suppressed % timestamp)))

(defn- expired?
  "True when the time between `:edge/occurred-at` and `now` exceeds
   `suppression-window-ms`."
  [edge now suppression-window-ms]
  (let [occurred (:edge/occurred-at edge)
        elapsed  (- (inst-ms now) (inst-ms occurred))]
    (> elapsed suppression-window-ms)))

(defn expire-pending
  "Scan the pending-edge index and transition any edge whose age exceeds
   `suppression-window-ms` to `:needs-operator` (§2.4 third transition,
   timeout arm).

   Returns `[new-state expired-edges]` where `expired-edges` is the vector
   of transitioned edge maps the emitter (N15-3) will publish.

   Pure: `now` is injected, no system clock."
  [state now suppression-window-ms]
  (let [pending  (:pending state)
        expired  (into []
                       (comp (filter (fn [[_ edge]]
                                       (expired? edge now suppression-window-ms)))
                             (map (fn [[_ edge]] (mark-needs-operator edge now))))
                       pending)
        evict    (into #{} (map :edge/trigger-event-id) expired)
        pending' (into {}
                       (remove (fn [[event-id _]] (contains? evict event-id)))
                       pending)]
    [(assoc state :pending pending') expired]))

(comment
  ;; REPL — observe → handle a single trigger
  (let [tid       (random-uuid)
        wf-id     (random-uuid)
        ts1       (java.util.Date.)
        ts2       (java.util.Date.)
        [s1 e1]   (apply-trigger empty-state
                                 {:trigger/event-id       tid
                                  :trigger/kind           :pr-merged
                                  :trigger/affected-pr-ids [["miniforge-ai/miniforge" 999]]
                                  :trigger/affected-agent-session-ids []}
                                 ts1)
        [s2 e2]   (apply-workflow-completed s1
                                            {:routing/trigger-event-id tid
                                             :workflow/id              wf-id
                                             :workflow/timestamp       ts2})]
    {:opened   e1
     :handled  e2
     :pending  (:pending s2)}))
