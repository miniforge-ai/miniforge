;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.web-dashboard.state.workflows
  "Workflow state from live and historical event streams."
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.watcher :as watcher]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(def events-dir-path
  "Default event archive directory shared by the CLI and dashboard."
  (str (System/getProperty "user.home") "/.miniforge/events"))

(defn normalize-ts
  "Normalize timestamp inputs to java.util.Date for UI rendering."
  [ts]
  (cond
    (instance? java.util.Date ts)
    ts

    (instance? java.time.Instant ts)
    (java.util.Date/from ts)

    (string? ts)
    (try
      (java.util.Date/from (java.time.Instant/parse ts))
      (catch Exception _ nil))

    :else nil))

(defn ->instant
  "Normalize timestamp inputs to java.time.Instant for comparisons."
  [ts]
  (cond
    (instance? java.time.Instant ts)
    ts

    (instance? java.util.Date ts)
    (.toInstant ^java.util.Date ts)

    (string? ts)
    (try
      (java.time.Instant/parse ts)
      (catch Exception _ nil))

    :else nil))

(defn ts-epoch-ms
  "Timestamp → epoch millis for sorting."
  [ts]
  (if-let [date (normalize-ts ts)]
    (.getTime ^java.util.Date date)
    0))

(defn wf-id
  [event]
  (or (:workflow/id event) (:workflow-id event)))

(defn workflow-id=
  "String-normalized workflow-id equality so UUID/string forms match."
  [expected event]
  (= (some-> expected str)
     (some-> (wf-id event) str)))

(defn event-ts
  "Extract the canonical event timestamp."
  [event]
  (or (:event/timestamp event) (:timestamp event)))

(defn workflow-event-file
  "Resolve the event file for a specific workflow-id."
  [workflow-id]
  (io/file events-dir-path (str workflow-id ".edn")))

(defn read-event-file
  "Read all parseable EDN events from a workflow event file."
  [file]
  (try
    (when (.exists ^java.io.File file)
      (with-open [reader (io/reader file)]
        (->> (line-seq reader)
             (keep watcher/parse-edn-line)
             vec)))
    (catch Exception _
      [])))

(defn dedupe-events
  "Deduplicate live + historical events by event-id, falling back to a
   composite key of stable fields when no id is present."
  [events]
  (->> events
       (reduce (fn [acc event]
                 (let [event-key (get event :event/id
                                      [(get event :event/type)
                                       (some-> (wf-id event) str)
                                       (event-ts event)
                                       (get event :event/sequence-number)
                                       (get event :message)])]
                   (assoc acc event-key event)))
               {})
       vals
       vec))

(defn wf-name-from-started
  "Extract a display name for a workflow from its started event."
  [started id]
  (let [spec (or (get started :workflow/spec)
                 (get started :workflow-spec)
                 (get started :spec))]
    (or (get spec :spec/title)
        (get spec :title)
        (get spec :name)
        (str "Workflow " (subs (str id) 0 (min 8 (count (str id))))))))

(defn event-phase
  "Extract the phase from an event, accepting both qualified and legacy keys."
  [event]
  (or (get event :workflow/phase) (get event :phase)))

(defn wf-phase
  "Derive the current phase of a workflow from its events."
  [events started]
  (or (event-phase started)
      (some->> events
               (filter #(#{:workflow/phase-started :workflow/phase-completed}
                          (:event/type %)))
               (sort-by #(ts-epoch-ms (event-ts %)))
               last
               event-phase)
      "unknown"))

(defn wf-status
  "Derive a workflow status keyword from terminal events and staleness."
  [completed failed last-event-ts]
  (cond
    failed     :failed
    completed  (let [status (or (:workflow/status completed) (:status completed))]
                 (if (#{:success :completed} status) :completed :failed))
    :else      (let [last-ts (normalize-ts last-event-ts)]
                 (if (and last-ts
                          (> (- (System/currentTimeMillis) (.getTime ^java.util.Date last-ts))
                             (* 10 60 1000)))
                   :stale
                   :running))))

(defn workflow-metrics
  "Aggregate workflow metrics from terminal and phase completion events.
   Prefers values on the :workflow/completed event; falls back to summing
   individual phase completion events."
  [events]
  (let [terminal (->> events
                      (filter #(= :workflow/completed (:event/type %)))
                      (sort-by #(ts-epoch-ms (event-ts %)))
                      last)
        phases (filter #(= :workflow/phase-completed (:event/type %)) events)
        total-tokens      (reduce + 0 (keep :phase/tokens phases))
        total-cost-usd    (reduce + 0 (keep :phase/cost-usd phases))
        total-duration-ms (reduce + 0 (keep :phase/duration-ms phases))
        tokens      (or (get terminal :workflow/tokens)      (when (pos? total-tokens) total-tokens))
        cost-usd    (or (get terminal :workflow/cost-usd)    (when (pos? total-cost-usd) total-cost-usd))
        duration-ms (or (get terminal :workflow/duration-ms) (when (pos? total-duration-ms) total-duration-ms))]
    (cond-> {}
      tokens      (assoc :tokens tokens)
      cost-usd    (assoc :cost-usd cost-usd)
      duration-ms (assoc :duration-ms duration-ms))))

(defn workflow-output-preview
  "Build a short preview from the latest streaming chunks."
  [events]
  (some->> events
           (filter #(= :agent/chunk (:event/type %)))
           (sort-by #(ts-epoch-ms (event-ts %)))
           (map :chunk/delta)
           (remove str/blank?)
           (take-last 12)
           (apply str)
           str/trim
           not-empty))

(def resolved-get-events
  "Cached reference to event-stream get-events fn."
  (delay
    (try
      (require 'ai.miniforge.event-stream.interface)
      (ns-resolve (find-ns 'ai.miniforge.event-stream.interface) 'get-events)
      (catch Exception _ nil))))

(defn live-stream-events
  "Read all currently buffered live events from the in-memory event stream."
  [state]
  (try
    (if-let [stream (:event-stream @state)]
      (if-let [get-events-fn @resolved-get-events]
        (get-events-fn stream)
        [])
      [])
    (catch Exception e
      (println "Error reading live workflow events:" (ex-message e))
      [])))

(defn historical-events
  "Read archived events from disk for one workflow or the full archive."
  [workflow-id]
  (if workflow-id
    (read-event-file (workflow-event-file workflow-id))
    (let [events-dir (io/file events-dir-path)]
      (if (.isDirectory events-dir)
        (->> (.listFiles events-dir)
             (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
             (mapcat read-event-file)
             vec)
        []))))

(defn filter-events
  "Filter and sort events newest-first."
  [events {:keys [workflow-id event-type since limit] :or {limit 100}}]
  (let [since-inst (when since (->instant since))]
    (->> events
         dedupe-events
         (filter (fn [event]
                   (and (or (nil? workflow-id)
                            (workflow-id= workflow-id event))
                        (or (nil? event-type)
                            (= event-type (:event/type event)))
                        (or (nil? since-inst)
                            (when-let [evt-inst (->instant (event-ts event))]
                              (not (.isBefore evt-inst since-inst)))))))
         (sort-by #(ts-epoch-ms (event-ts %)) >)
         (take limit)
         vec)))

;------------------------------------------------------------------------------ Layer 1
;; Data fetchers

(def get-workflows
  "Get workflows from the live event stream (cached 5s)."
  (let [ttl-ms 5000
        cache (atom {})]
    (fn [state]
      (let [now (System/currentTimeMillis)
            cached (get @cache [state])]
        (if (and cached (< (- now (:time cached)) ttl-ms))
          (:value cached)
          (let [result (try
                         (let [events (live-stream-events state)
                               grouped-events (group-by wf-id (filter (comp some? wf-id) events))
                               workflows (map (fn [[id wf-events]]
                                                (let [wf-events (->> wf-events
                                                                     (sort-by #(ts-epoch-ms (event-ts %)))
                                                                     vec)
                                                      started (first (filter #(= :workflow/started (:event/type %))
                                                                             wf-events))
                                                      completed (last (filter #(= :workflow/completed (:event/type %))
                                                                              wf-events))
                                                      failed (last (filter #(= :workflow/failed (:event/type %))
                                                                           wf-events))
                                                      started-ts (event-ts started)
                                                      completed-ts (event-ts completed)
                                                      last-event-ts (some-> wf-events last event-ts)
                                                      status (wf-status completed failed last-event-ts)
                                                      metrics (workflow-metrics wf-events)
                                                      output-preview (workflow-output-preview wf-events)]
                                                  (cond-> {:id id
                                                           :name (wf-name-from-started started id)
                                                           :status status
                                                           :phase (wf-phase wf-events started)
                                                           :progress (if (#{:completed :failed :stale} status)
                                                                       100
                                                                       50)
                                                           :started-at (normalize-ts started-ts)
                                                           :updated-at (normalize-ts last-event-ts)
                                                           :completed-at (normalize-ts completed-ts)
                                                           :event-count (count wf-events)
                                                           :evidence-bundle-id (:workflow/evidence-bundle-id completed)
                                                           :metrics metrics}
                                                    output-preview
                                                    (assoc :latest-output output-preview))))
                                              grouped-events)]
                           (->> workflows
                                (sort-by (fn [workflow]
                                           (or (some-> (:updated-at workflow) .getTime)
                                               (some-> (:started-at workflow) .getTime)
                                               0))
                                         >)
                                (take 50)
                                vec))
                         (catch Exception e
                           (println "Error getting workflows:" (ex-message e))
                           []))]
            (swap! cache assoc [state] {:value result :time now})
            result))))))

(defn get-workflow-detail
  "Get workflow detail by string id."
  [state id]
  (let [workflows (get-workflows state)]
    (or (first (filter #(= (str (:id %)) id) workflows))
        {:error "Workflow not found"})))

(defn get-events
  "Query raw events from live memory plus archived event files.

   Options:
   - :workflow-id  Filter by workflow UUID or string id
   - :event-type   Filter by event type keyword
   - :since        Filter events after this timestamp
   - :limit        Max events to return (default 100)"
  [state {:keys [workflow-id] :as opts}]
  (try
    (let [all-events (into (live-stream-events state)
                           (historical-events workflow-id))]
      (filter-events all-events opts))
    (catch Exception e
      (println "Error querying events:" (ex-message e))
      [])))

;------------------------------------------------------------------------------ Layer 2
;; Workflow command queue

(defn enqueue-command!
  "Enqueue a control command for a workflow.
   Commands: :pause, :resume, :stop"
  [state workflow-id command]
  (swap! state update-in [:workflow-commands (str workflow-id)]
         (fnil conj [])
         {:command (keyword command)
          :timestamp (System/currentTimeMillis)}))

(defn dequeue-commands!
  "Atomically dequeue all pending commands for a workflow.
   Returns the commands and removes them from the queue."
  [state workflow-id]
  (let [wf-id (str workflow-id)
        commands (get-in @state [:workflow-commands wf-id] [])]
    (when (seq commands)
      (swap! state update :workflow-commands dissoc wf-id))
    commands))
