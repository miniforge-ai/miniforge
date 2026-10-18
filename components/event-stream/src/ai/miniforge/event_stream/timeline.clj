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
(ns ai.miniforge.event-stream.timeline
  "Pure namespace: transforms a seq of parsed event maps into a
   human-readable timeline string.

   Input: event maps as returned by `reader/read-workflow-events`.
   Output: a multi-line string. No IO, no side effects.

   Column layout per event line:
     HH:mm:ss  <phase>  <tool-name>  <args-summary>

   Gap detection: consecutive events whose timestamp gap exceeds
   `gap-threshold-ms` (default 60 000 ms) produce an inserted gap line.

   Every user-facing label/marker (status words, missing-value
   sentinels, format templates) flows through `messages/t` per
   .standards/foundations/localization.mdc — the catalog lives at
   `resources/config/event-stream/messages/en-US.edn` under the
   `:timeline/*` namespace."
  (:require
   [clojure.string :as str]
   [ai.miniforge.event-stream.messages :as messages]
   [ai.miniforge.event-stream.timeline-values :as values]))

;------------------------------------------------------------------------------ Layer 0

;; Constants
(def ^{:stratum 0} ^:const default-gap-threshold-ms
  "Default gap threshold in milliseconds (60 seconds)."
  60000)

(def ^{:stratum 0} ^:const args-preview-length
  "Maximum character length for the args-summary column."
  values/args-preview-length)

(def ^{:stratum 0} ^:private terminal-event-types
  "Event types that denote workflow termination."
  #{:workflow/completed :workflow/failed})

(def ^{:stratum 0} ^:private stall-event-types
  "Event types that denote stream stalls."
  #{:agent/stream-stalled})

(def ^{:stratum 0} ^:private phase-lifecycle-types
  "Event types for phase start/stop lifecycle."
  #{:workflow/phase-started :workflow/phase-completed})

(defn- ^{:stratum 0} index-tool-names
  "Build a `{tool-call-id → tool-name}` lookup map from the event stream.

   `:tool/call-completed` events don't carry the tool name (per the
   `ToolCallCompleted` schema) — the name lives on the paired
   `:agent/tool-call-started` event. Pre-walk the stream once so the
   completed-event renderer can look the name up without re-scanning."
  [events]
  (->> events
       (filter #(and (= :agent/tool-call-started (values/event-type %))
                     (:tool/call-id %)
                     (:tool/name %)))
       (reduce (fn [m e] (assoc m (:tool/call-id e) (:tool/name e)))
               {})))

;; Per-event-type render dispatch
(defn- ^{:stratum 0} render-tool-call-started
  "`:agent/tool-call-started` — show tool name + args digest preview."
  [event]
  (let [ts       (values/format-hms (values/event-timestamp event))
        phase    (values/event-phase event)
        tool     (or (:tool/name event) (messages/t :timeline/unknown-tool))
        args-sum (values/args-summary event)]
    (format "%s  %s  %s  %s" ts phase tool args-sum)))

(defn- ^{:stratum 0} render-tool-call-completed
  "`:tool/call-completed` — show tool name + duration + success/error.

   Per `schema/ToolCallCompleted` the event does NOT carry `:tool/name`;
   the name lives on the paired `:agent/tool-call-started` event,
   correlated via `:tool/call-id`. `tool-names-by-call-id` is the
   correlation map built by `render-timeline` as it walks the stream.
   Fallbacks (in order): correlated name → `:tool/call-id` →
   localized `:timeline/unknown-tool` sentinel.

   Status is sourced from `:tool/success?` (per the schema), with
   `:tool/error` presence as the secondary signal for legacy events
   that omitted the boolean."
  [event tool-names-by-call-id]
  (let [ts       (values/format-hms (values/event-timestamp event))
        phase    (values/event-phase event)
        call-id  (:tool/call-id event)
        tool     (or (get tool-names-by-call-id call-id)
                     call-id
                     (messages/t :timeline/unknown-tool))
        dur-ms   (:tool/duration-ms event)
        status   (cond
                   (false? (:tool/success? event)) (messages/t :timeline/status-error)
                   (true?  (:tool/success? event)) (messages/t :timeline/status-success)
                   (some? (:tool/error event))     (messages/t :timeline/status-error)
                   :else                           (messages/t :timeline/status-success))
        dur-str  (if dur-ms
                   (str "  " (values/format-duration-ms dur-ms) "  " status)
                   (str "  " status))]
    (format "%s  %s  %s%s" ts phase tool dur-str)))

(defn- ^{:stratum 0} render-phase-lifecycle
  "`:workflow/phase-started` / `:workflow/phase-completed` — phase lifecycle."
  [event]
  (let [ts       (values/format-hms (values/event-timestamp event))
        phase    (values/event-phase event)
        ev-type  (values/event-type event)
        suffix   (case ev-type
                   :workflow/phase-started   (messages/t :timeline/phase-suffix-started)
                   :workflow/phase-completed (messages/t :timeline/phase-suffix-completed)
                   (name ev-type))
        marker   (messages/t :timeline/phase-marker {:suffix suffix})
        msg      (values/event-message event)]
    (if (seq msg)
      (format "%s  %s  %s  %s" ts phase marker msg)
      (format "%s  %s  %s" ts phase marker))))

(defn- ^{:stratum 0} render-terminal
  "`:workflow/completed` / `:workflow/failed` — terminal status with the
   localized terminated marker."
  [event]
  (let [ts     (values/format-hms (values/event-timestamp event))
        phase  (values/event-phase event)
        marker (messages/t :timeline/terminated-marker)
        reason (or (not-empty (values/event-message event))
                   (when-let [r (:workflow/result event)]
                     (str r))
                   "")]
    (format "%s  %s  %s  %s" ts phase marker reason)))

(defn- ^{:stratum 0} render-stall
  "`:agent/stream-stalled` — stall marker."
  [event]
  (let [ts     (values/format-hms (values/event-timestamp event))
        phase  (values/event-phase event)
        marker (messages/t :timeline/stall-marker)
        msg    (or (not-empty (values/event-message event))
                   (messages/t :timeline/stall-default-msg))]
    (format "%s  %s  %s  %s" ts phase marker msg)))

;; Gap line rendering
(defn- ^{:stratum 0} render-gap-line
  "Format a gap line between two events with timestamps `ts-a` and `ts-b`
   and a computed gap of `gap-ms` milliseconds."
  [ts-a ts-b gap-ms]
  (messages/t :timeline/gap-line
              {:ts-a (values/format-hms ts-a)
               :ts-b (values/format-hms ts-b)
               :gap  (values/format-duration-ms gap-ms)}))

(defn- ^{:stratum 0} render-generic
  "Catch-all for event types without a specific renderer."
  [event]
  (let [ts       (values/format-hms (values/event-timestamp event))
        phase    (values/event-phase event)
        ev-type  (or (values/event-type event) (messages/t :timeline/unknown-event-type))
        msg      (values/event-message event)]
    (format "%s  %s  %s  %s" ts phase (str ev-type) (values/truncate msg values/args-preview-length))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} render-event
  "Dispatch to the appropriate per-event-type renderer.

   `tool-names-by-call-id` is the correlation map render-timeline
   builds as it walks the event stream — only consulted by the
   completed-event renderer, but threaded through every dispatch so
   the signature stays uniform."
  [tool-names-by-call-id event]
  (let [et (values/event-type event)]
    (cond
      (contains? terminal-event-types et)   (render-terminal event)
      (contains? stall-event-types et)      (render-stall event)
      (contains? phase-lifecycle-types et)  (render-phase-lifecycle event)
      (= et :agent/tool-call-started)       (render-tool-call-started event)
      (= et :tool/call-completed)           (render-tool-call-completed event tool-names-by-call-id)
      :else                                 (render-generic event))))

(defn- ^{:stratum 1} append-event [render gap-threshold {:keys [prev-ts lines]} event]
  (let [cur-ts (values/ts->epoch-ms (values/event-timestamp event))
        gap-line (when (and prev-ts cur-ts (> (- cur-ts prev-ts) gap-threshold))
                   (render-gap-line prev-ts cur-ts (- cur-ts prev-ts)))
        event-line (render event)
        next-lines (cond-> lines
                     gap-line (conj gap-line)
                     event-line (conj event-line))]
    ;; Missing timestamps break adjacency; do not bridge gaps across them.
    {:prev-ts cur-ts
     :lines next-lines}))

;------------------------------------------------------------------------------ Layer 2

;; Public API
(defn ^{:stratum 2} render-timeline
  "Transform a seq of parsed event maps into a human-readable timeline string.

   Each event renders as one line:
     HH:mm:ss  <phase>  <detail>

   Gap lines are inserted between consecutive events whose timestamp gap
   exceeds `gap-threshold-ms` (default 60 000 ms):
     HH:mm:ss→HH:mm:ss — Xm Ys event-stream gap (stalled)

   Terminal events render with TERMINATED marker.

   Arity:
     (render-timeline events)        — default 60s gap threshold
     (render-timeline events opts)   — opts: {:gap-threshold-ms long}

   Returns a string (empty string for empty / nil input — consistent
   return type so callers don't have to nil-check before
   `println`/`spit`). Pure function — no IO."
  ([events]
   (render-timeline events {}))
  ([events opts]
   (let [gap-threshold (get opts :gap-threshold-ms default-gap-threshold-ms)
         render (partial render-event (index-tool-names events))
         rows (reduce (partial append-event render gap-threshold) {:prev-ts nil :lines []} events)]
     (str/join "\n" (:lines rows)))))

;------------------------------------------------------------------------------ Rich comment
(comment
  ;; Example usage:
  (require '[ai.miniforge.event-stream.reader :as reader])
  (let [events (reader/read-workflow-events "/tmp/my-workflow")]
    (println (render-timeline events)))

  (render-timeline [] {})
  ;; => ""

  (render-timeline
   [{:event/type      :agent/tool-call-started
     :event/timestamp (java.util.Date.)
     :workflow/phase  :implement
     :tool/name       "Write"
     :tool/args-digest {:digest/preview "Writing src/foo.clj"}}])
  ;; => "HH:mm:ss  implement  Write  Writing src/foo.clj"
  )
