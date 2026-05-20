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

(ns ai.miniforge.cli.main.commands.events
  "CLI command: miniforge events show <workflow-id>.

   Renders a human-readable event timeline for a completed or in-flight
   workflow.  Each row shows: timestamp | event-type | component | summary.

   Flags:
     --json                   Machine-readable JSON output (one object per line)
     --type-filter <str>      Show only events whose :event/type name contains
                              the given string (e.g. --type-filter tool-call)
     --gap-threshold <secs>   Highlight time gaps larger than N seconds
                              (default: 60)"
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.event-stream.interface :as es]))

;;------------------------------------------------------------------------------ Layer 0
;; ANSI helpers
;;
;; ESC byte constructed via (char 27) to avoid any source-encoding ambiguity.
;; Every terminal colour code is: ESC + "[" + code + "m".

(def ^:private esc-byte
  "The ESC control character (U+001B / char 27) as a one-character string."
  (str (char 27)))

(defn- ansi-code
  "Build a complete ANSI escape sequence, e.g. (ansi-code \"36m\") → ESC[36m."
  [suffix]
  (str esc-byte "[" suffix))

(def ^:private ansi
  {:reset   (ansi-code "0m")
   :bold    (ansi-code "1m")
   :dim     (ansi-code "2m")
   :cyan    (ansi-code "36m")
   :green   (ansi-code "32m")
   :yellow  (ansi-code "33m")
   :red     (ansi-code "31m")
   :gray    (ansi-code "90m")
   :blue    (ansi-code "34m")
   :magenta (ansi-code "35m")})

(defn- c
  "Apply ANSI color `k` to `text`, then reset."
  [k text]
  (str (get ansi k "") text (:reset ansi)))

;;------------------------------------------------------------------------------ Layer 1
;; Timestamp helpers

(defn- ts->instant
  "Coerce an :event/timestamp value to a java.time.Instant, or nil."
  [ts]
  (cond
    (instance? java.time.Instant ts) ts
    (instance? java.util.Date ts)    (.toInstant ^java.util.Date ts)
    (string? ts)
    (try (java.time.Instant/parse ts) (catch Exception _ nil))
    :else nil))

(defn- instant->ms
  "Epoch millis from an Instant, or nil."
  [inst]
  (when inst (.toEpochMilli ^java.time.Instant inst)))

(defn- format-ts
  "Render an :event/timestamp field as HH:MM:SS.mmm.
   Returns \"??:??:??.???\" on parse failure."
  [ts]
  (if-let [inst (ts->instant ts)]
    (let [fmt (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")]
      (.format (java.time.LocalDateTime/ofInstant inst (java.time.ZoneId/systemDefault))
               fmt))
    (let [s (str ts)]
      (if (re-find #"\d\d:\d\d:\d\d" s)
        (first (re-find #"(\d\d:\d\d:\d\d)" s))
        "??:??:??.???"))))

;;------------------------------------------------------------------------------ Layer 2
;; Event classification

(defn- event-type-name
  "String name of the event type.  Guards against bare (non-namespaced) keywords
   which would produce a spurious leading slash via (namespace t) → nil."
  [ev]
  (let [t (:event/type ev)]
    (cond
      (keyword? t)
      (if (namespace t)
        (str (namespace t) "/" (name t))
        (name t))

      (string? t) t
      :else        (str t))))

(defn- event-component
  "Best-effort component field from an event map."
  [ev]
  (or (some-> (:event/component ev) name)
      (some-> (:workflow/phase ev) name)
      (some-> (:agent/role ev) name)
      ""))

(defn- summarize
  "One-line summary for a single event, emphasising the most interesting fields."
  [ev]
  (let [t          (:event/type ev)
        tool       (:tool/name ev)
        tool-names (:tool/names ev)
        phase      (:workflow/phase ev)
        outcome    (:phase/outcome ev)
        duration   (:phase/duration-ms ev)
        err        (or (:phase/error ev)
                       (get-in ev [:dag/diagnostic :result/error :error/message])
                       (:error/message ev)
                       (:message ev))]
    (cond
      (= t :agent/tool-call-started)
      (str "-> " (or tool "(unknown)"))

      (= t :agent/tool-call-completed)
      (str "<- " (or tool "(unknown)")
           (when duration (format " (%.2fs)" (/ (double duration) 1000.0))))

      (= t :workflow/phase-started)
      (str "-> phase " (some-> phase name))

      (= t :workflow/phase-completed)
      (str "phase " (some-> phase name)
           " " (some-> outcome name)
           (when duration (format " (%.1fs)" (/ (double duration) 1000.0)))
           (when err (str " - " (subs (str err) 0 (min 120 (count (str err)))))))

      (= t :workflow/started)
      "workflow started"

      (= t :workflow/completed)
      (str "workflow completed - " (some-> (:workflow/status ev) name))

      (= t :workflow/failed)
      (str "workflow failed - " (or (:workflow/failure-reason ev) err ""))

      (= t :agent/heartbeat)
      "heartbeat"

      (= t :agent/tool-call)
      (str "tool " (or tool
                       (when (seq tool-names) (str/join "," (map str tool-names)))
                       "(unnamed)"))

      (= t :agent/status)
      (str (or (:status/type ev) "status") " - " (or (:message ev) ""))

      (= t :agent/chunk)
      (str "chunk " (if (:chunk/done? ev) "done" "streaming"))

      (= t :workflow/dag-considered)
      (str "DAG " (some-> (:dag/outcome ev) name)
           (when-let [r (:dag/reason ev)] (str " - " (str r))))

      err
      (str (event-type-name ev) " - " (subs (str err) 0 (min 120 (count (str err)))))

      :else
      (event-type-name ev))))

;;------------------------------------------------------------------------------ Layer 3
;; Row colorization

(defn- row-color
  "Return the ANSI key for coloring a timeline row, or nil for default color."
  [ev]
  (let [t (:event/type ev)]
    (cond
      (#{:agent/tool-call-started :agent/tool-call-completed :agent/tool-call} t) :cyan
      (#{:agent/heartbeat} t)                                                      :dim
      (#{:workflow/failed :agent/error} t)                                         :red
      (and (keyword? t) (= "error" (name t)))                                      :red
      (#{:workflow/started :workflow/completed
         :workflow/phase-started :workflow/phase-completed} t)                     :green
      :else nil)))

;;------------------------------------------------------------------------------ Layer 4
;; Gap detection

(defn- gap-row
  "Build a synthetic gap-marker row for display."
  [gap-secs]
  {:gap? true :gap-secs gap-secs})

(defn- compute-gap-ms
  "Compute millisecond gap between two events.  Returns nil when either
   timestamp is unparseable."
  [prev-ev cur-ev]
  (let [prev-ms (some-> (:event/timestamp prev-ev) ts->instant instant->ms)
        cur-ms  (some-> (:event/timestamp cur-ev)  ts->instant instant->ms)]
    (when (and prev-ms cur-ms) (- cur-ms prev-ms))))

(defn- inject-gaps
  "Insert synthetic gap markers between consecutive events that are more than
   `threshold-ms` milliseconds apart.

   Tracks the last *real* event separately from the accumulator so that
   back-to-back large gaps are all detected — O(n) time overall."
  [events threshold-ms]
  (loop [result    []
         prev-real nil
         remaining events]
    (if (empty? remaining)
      result
      (let [ev     (first remaining)
            gap-ms (when prev-real (compute-gap-ms prev-real ev))]
        (recur (cond-> result
                 (and gap-ms (> gap-ms threshold-ms))
                 (conj (gap-row (/ (double gap-ms) 1000.0)))
                 true
                 (conj ev))
               ev
               (rest remaining))))))

;;------------------------------------------------------------------------------ Layer 5
;; Filter helpers

(defn- type-matches-filter?
  "True when the event type name contains `filter-str` as a substring."
  [ev filter-str]
  (str/includes? (event-type-name ev) (str filter-str)))

(defn- apply-filters
  "Apply the user-supplied type-filter/no-chunks/no-status opts to a seq of events.
   Uses `:type-filter` (not `:filter`) to avoid shadowing clojure.core/filter."
  [events {:keys [type-filter no-chunks no-status]
           :or   {no-chunks true no-status false}}]
  (cond->> events
    type-filter (filter #(type-matches-filter? % type-filter))
    no-chunks   (remove #(= :agent/chunk (:event/type %)))
    no-status   (remove #(= :agent/status (:event/type %)))))

;;------------------------------------------------------------------------------ Layer 6
;; Rendering

(def ^:private col-ts-width    12)
(def ^:private col-type-width  36)
(def ^:private col-comp-width  16)

(defn- pad-right
  "Right-pad string `s` to `n` characters."
  [s n]
  (let [s (str s)]
    (if (>= (count s) n)
      (subs s 0 n)
      (str s (apply str (repeat (- n (count s)) " "))))))

(defn- render-header []
  (let [header (str (pad-right "TIMESTAMP"  col-ts-width)  "  "
                    (pad-right "EVENT-TYPE" col-type-width) "  "
                    (pad-right "COMPONENT"  col-comp-width) "  "
                    "SUMMARY")]
    (println (c :bold header))
    (println (c :gray (apply str (repeat 100 "-"))))))

(defn- render-gap-row [gap-secs]
  (let [label (format "  v gap: %.1fs" gap-secs)
        pad   (apply str (repeat (max 0 (- 90 (count label))) " "))]
    (println (c :yellow (str label pad)))))

(defn- render-event-row [ev]
  (let [ts-str   (format-ts (:event/timestamp ev))
        type-str (event-type-name ev)
        comp-str (event-component ev)
        sum-str  (summarize ev)
        row      (str (pad-right ts-str   col-ts-width) "  "
                      (pad-right type-str col-type-width) "  "
                      (pad-right comp-str col-comp-width) "  "
                      sum-str)
        color    (row-color ev)]
    (if color
      (println (c color row))
      (println row))))

;;------------------------------------------------------------------------------ Layer 7
;; JSON output

(defn- prep-for-json
  "Recursively convert an event map to a JSON-safe plain-data structure:
     keywords   -> strings  (with leading colon for round-trip readability)
     Instants   -> ISO-8601 strings
     Dates      -> ISO-8601 strings
     UUIDs      -> strings
     nils       -> nil  (serialises as JSON null, NOT the string \"nil\")"
  [x]
  (cond
    (map? x)
    (reduce-kv (fn [acc k v]
                 (assoc acc (str k) (prep-for-json v)))
               {}
               x)

    (sequential? x)
    (mapv prep-for-json x)

    (nil? x)       nil
    (keyword? x)   (str x)
    (uuid? x)      (str x)

    (instance? java.time.Instant x)
    (.toString ^java.time.Instant x)

    (instance? java.util.Date x)
    (.toString (.toInstant ^java.util.Date x))

    :else x))

(defn- render-json
  "Emit one JSON object per line to stdout.
   Logs per-event serialisation failures to stderr rather than swallowing them,
   then falls back to pr-str for that individual event."
  [events]
  (doseq [ev events]
    (try
      (println (json/generate-string (prep-for-json ev)))
      (catch Exception e
        (binding [*out* *err*]
          (println "WARN events show: failed to serialise event to JSON -"
                   (ex-message e)))
        (println (pr-str ev))))))

;;------------------------------------------------------------------------------ Layer 8
;; Public entry point

(defn events-show-cmd
  "Implement `miniforge events show <workflow-id>`.

   Reads workflow events from the file sink, renders a human-readable
   timeline with optional gap detection and JSON output.

   Opts (all optional):
     :workflow-id    - string or UUID (required; passed positionally)
     :json           - boolean; emit one JSON object per line
     :type-filter    - string; include only events whose type contains this
     :gap-threshold  - number of seconds; highlight gaps larger than N (default: 60)
     :no-chunks      - boolean; suppress :agent/chunk rows (default: true)
     :no-status      - boolean; suppress :agent/status rows (default: false)"
  [{:keys [workflow-id json type-filter gap-threshold no-chunks no-status]
    :or   {gap-threshold 60 no-chunks true no-status false}}]
  (if-not workflow-id
    (do
      (display/print-error (messages/t :events/missing-workflow-id))
      (println (messages/t :events/usage)))
    (let [events-dir (app-config/events-dir)
          raw-events (es/read-workflow-events-by-id events-dir workflow-id)]
      (if (empty? raw-events)
        (display/print-error
         (messages/t :events/no-events-found
                     {:workflow-id workflow-id
                      :events-dir  events-dir}))
        (let [filter-opts  {:type-filter type-filter
                            :no-chunks   no-chunks
                            :no-status   no-status}
              filtered     (apply-filters raw-events filter-opts)
              threshold-ms (* (or gap-threshold 60) 1000)]
          (if json
            (render-json filtered)
            (do
              (println (c :cyan
                          (messages/t :events/timeline-header
                                      {:workflow-id workflow-id
                                       :count       (count filtered)
                                       :filter-note (if type-filter
                                                      (str "  [filter: " type-filter "]")
                                                      "")})))
              (render-header)
              (doseq [row (inject-gaps filtered threshold-ms)]
                (if (:gap? row)
                  (render-gap-row (:gap-secs row))
                  (render-event-row row))))))))))

;; Rich comment - development convenience
(comment
  ;; Show a workflow timeline
  (events-show-cmd {:workflow-id "some-wf-id"})

  ;; Filter to tool calls only
  (events-show-cmd {:workflow-id "some-wf-id" :type-filter "tool-call"})

  ;; Machine-readable JSON
  (events-show-cmd {:workflow-id "some-wf-id" :json true})

  ;; Highlight gaps > 30 s
  (events-show-cmd {:workflow-id "some-wf-id" :gap-threshold 30}))
