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
   [ai.miniforge.event-stream.messages :as messages])
  (:import
   [java.text SimpleDateFormat]
   [java.util Date TimeZone]))

;------------------------------------------------------------------------------ Layer 0
;; Constants

(def ^:const default-gap-threshold-ms
  "Default gap threshold in milliseconds (60 seconds)."
  60000)

(def ^:const args-preview-length
  "Maximum character length for the args-summary column."
  60)

(def ^:private terminal-event-types
  "Event types that denote workflow termination."
  #{:workflow/completed :workflow/failed})

(def ^:private stall-event-types
  "Event types that denote stream stalls."
  #{:agent/stream-stalled})

(def ^:private phase-lifecycle-types
  "Event types for phase start/stop lifecycle."
  #{:workflow/phase-started :workflow/phase-completed})

;------------------------------------------------------------------------------ Layer 0
;; Time formatting helpers

(defn- make-hms-formatter
  "Create a thread-local HH:mm:ss formatter in UTC."
  ^SimpleDateFormat []
  (doto (SimpleDateFormat. "HH:mm:ss")
    (.setTimeZone (TimeZone/getTimeZone "UTC"))))

(def ^:private ^ThreadLocal hms-formatter-local
  "Thread-local SimpleDateFormat to avoid allocation on hot paths."
  (proxy [ThreadLocal] []
    (initialValue [] (make-hms-formatter))))

(defn- format-hms
  "Format `ts` (a Date, long epoch-ms, or ISO-8601 string) as HH:mm:ss.
   Returns the localized `:timeline/unknown-time` sentinel when `ts` is
   nil or unparseable."
  [ts]
  (try
    (let [^SimpleDateFormat fmt (.get hms-formatter-local)
          ^Date d (cond
                    (nil? ts)    nil
                    (instance? Date ts) ts
                    (number? ts) (Date. (long ts))
                    (string? ts) (-> (java.time.Instant/parse ts)
                                     (.toEpochMilli)
                                     (Date.))
                    :else        nil)]
      (if d
        (.format fmt d)
        (messages/t :timeline/unknown-time)))
    (catch Exception _
      (messages/t :timeline/unknown-time))))

(defn- ts->epoch-ms
  "Coerce `ts` to epoch-ms long. Returns nil on failure."
  [ts]
  (try
    (cond
      (nil? ts)            nil
      (instance? Date ts)  (.getTime ^Date ts)
      (number? ts)         (long ts)
      (string? ts)         (-> (java.time.Instant/parse ts)
                               (.toEpochMilli))
      :else                nil)
    (catch Exception _
      nil)))

;------------------------------------------------------------------------------ Layer 1
;; Field extraction helpers

(defn- event-timestamp [event]
  (:event/timestamp event))

(defn- event-phase [event]
  (or (:workflow/phase event) (messages/t :timeline/no-phase)))

(defn- event-type [event]
  (:event/type event))

(defn- truncate
  "Truncate string `s` to at most `n` characters, appending the
   localized `:timeline/truncation-suffix` if cut."
  [s n]
  (when (string? s)
    (if (<= (count s) n)
      s
      (let [suffix        (str (messages/t :timeline/truncation-suffix))
            suffix-length (min (count suffix) (max 0 n))
            prefix-length (max 0 (- n suffix-length))]
        (str (subs s 0 prefix-length)
             (subs suffix 0 suffix-length))))))

(defn- args-summary
  "Extract the args preview from an event, truncated to `args-preview-length` chars.
   Prefers `:tool/args-digest :digest/preview`, falls back to `:message`."
  [event]
  (let [preview (get-in event [:tool/args-digest :digest/preview])
        raw     (or preview (:message event) "")]
    (truncate (str raw) args-preview-length)))

;------------------------------------------------------------------------------ Layer 1
;; Duration helpers

(defn- format-duration-ms
  "Format `ms` as a human-readable duration string. Both shapes
   (mins+secs, secs-only) flow through the user catalog."
  [ms]
  (let [total-s (long (/ ms 1000))
        m       (long (/ total-s 60))
        s       (long (rem total-s 60))]
    (if (pos? m)
      (messages/t :timeline/duration-mins-secs {:mins m :secs s})
      (messages/t :timeline/duration-secs      {:secs s}))))

;------------------------------------------------------------------------------ Layer 2
;; Per-event-type render dispatch

(defn- render-tool-call-started
  "`:agent/tool-call-started` — show tool name + args digest preview."
  [event]
  (let [ts       (format-hms (event-timestamp event))
        phase    (event-phase event)
        tool     (or (:tool/name event) (messages/t :timeline/unknown-tool))
        args-sum (args-summary event)]
    (format "%s  %s  %s  %s" ts phase tool args-sum)))

(defn- render-tool-call-completed
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
  (let [ts       (format-hms (event-timestamp event))
        phase    (event-phase event)
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
                   (str "  " (format-duration-ms dur-ms) "  " status)
                   (str "  " status))]
    (format "%s  %s  %s%s" ts phase tool dur-str)))

(defn- render-phase-lifecycle
  "`:workflow/phase-started` / `:workflow/phase-completed` — phase lifecycle."
  [event]
  (let [ts       (format-hms (event-timestamp event))
        phase    (event-phase event)
        ev-type  (event-type event)
        suffix   (case ev-type
                   :workflow/phase-started   (messages/t :timeline/phase-suffix-started)
                   :workflow/phase-completed (messages/t :timeline/phase-suffix-completed)
                   (name ev-type))
        marker   (messages/t :timeline/phase-marker {:suffix suffix})
        msg      (or (:message event) "")]
    (if (seq msg)
      (format "%s  %s  %s  %s" ts phase marker msg)
      (format "%s  %s  %s" ts phase marker))))

(defn- render-terminal
  "`:workflow/completed` / `:workflow/failed` — terminal status with the
   localized terminated marker."
  [event]
  (let [ts     (format-hms (event-timestamp event))
        phase  (event-phase event)
        marker (messages/t :timeline/terminated-marker)
        reason (or (:message event)
                   (when-let [r (:workflow/result event)]
                     (str r))
                   "")]
    (format "%s  %s  %s  %s" ts phase marker reason)))

(defn- render-stall
  "`:agent/stream-stalled` — stall marker."
  [event]
  (let [ts     (format-hms (event-timestamp event))
        phase  (event-phase event)
        marker (messages/t :timeline/stall-marker)
        msg    (or (:message event) (messages/t :timeline/stall-default-msg))]
    (format "%s  %s  %s  %s" ts phase marker msg)))

(defn- render-generic
  "Catch-all for event types without a specific renderer."
  [event]
  (let [ts       (format-hms (event-timestamp event))
        phase    (event-phase event)
        ev-type  (or (event-type event) (messages/t :timeline/unknown-tool))
        msg      (or (:message event) "")]
    (format "%s  %s  %s  %s" ts phase (str ev-type) (truncate msg args-preview-length))))

(defn- render-event
  "Dispatch to the appropriate per-event-type renderer.

   `tool-names-by-call-id` is the correlation map render-timeline
   builds as it walks the event stream — only consulted by the
   completed-event renderer, but threaded through every dispatch so
   the signature stays uniform."
  [event tool-names-by-call-id]
  (let [et (event-type event)]
    (cond
      (contains? terminal-event-types et)   (render-terminal event)
      (contains? stall-event-types et)      (render-stall event)
      (contains? phase-lifecycle-types et)  (render-phase-lifecycle event)
      (= et :agent/tool-call-started)       (render-tool-call-started event)
      (= et :tool/call-completed)           (render-tool-call-completed event tool-names-by-call-id)
      :else                                 (render-generic event))))

;------------------------------------------------------------------------------ Layer 2
;; Gap line rendering

(defn- render-gap-line
  "Format a gap line between two events with timestamps `ts-a` and `ts-b`
   and a computed gap of `gap-ms` milliseconds."
  [ts-a ts-b gap-ms]
  (messages/t :timeline/gap-line
              {:ts-a (format-hms ts-a)
               :ts-b (format-hms ts-b)
               :gap  (format-duration-ms gap-ms)}))

;------------------------------------------------------------------------------ Layer 3
;; Public API

(defn- index-tool-names
  "Build a `{tool-call-id → tool-name}` lookup map from the event stream.

   `:tool/call-completed` events don't carry the tool name (per the
   `ToolCallCompleted` schema) — the name lives on the paired
   `:agent/tool-call-started` event. Pre-walk the stream once so the
   completed-event renderer can look the name up without re-scanning."
  [events]
  (->> events
       (filter #(and (= :agent/tool-call-started (event-type %))
                     (:tool/call-id %)
                     (:tool/name %)))
       (reduce (fn [m e] (assoc m (:tool/call-id e) (:tool/name e)))
               {})))

(defn render-timeline
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
   (if (empty? events)
     ""
     (let [gap-threshold (get opts :gap-threshold-ms default-gap-threshold-ms)
           name-by-id    (index-tool-names events)
           lines         (reduce
                          (fn [{:keys [prev-ts lines]} event]
                            (let [cur-ts     (ts->epoch-ms (event-timestamp event))
                                  gap-line   (when (and prev-ts cur-ts
                                                        (> (- cur-ts prev-ts) gap-threshold))
                                               (render-gap-line prev-ts cur-ts (- cur-ts prev-ts)))
                                  event-line (render-event event name-by-id)]
                              ;; Update `:prev-ts` to `cur-ts` (allowing
                              ;; nil) rather than clinging to the last
                              ;; non-nil value — otherwise an event with
                              ;; no timestamp injected between two
                              ;; timestamped events fires a phantom gap
                              ;; line spanning across it.
                              {:prev-ts cur-ts
                               :lines   (cond-> lines
                                          gap-line   (conj gap-line)
                                          event-line (conj event-line))}))
                          {:prev-ts nil :lines []}
                          events)]
       (clojure.string/join "\n" (:lines lines))))))

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
