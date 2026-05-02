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
   [ai.miniforge.config.interface :as config]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.watcher :as watcher]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(def events-dir-path
  "Default event archive directory shared by the CLI and dashboard."
  (str (config/miniforge-home) "/events"))

(def stale-threshold-ms
  "Workflows with no events for this long are considered stale (10 minutes)."
  (* 10 60 1000))

(def max-recent-workflows
  "Maximum number of recent workflows returned from the live stream."
  50)

(def phase-lifecycle-types
  "Event types that carry phase classification data."
  #{:workflow/phase-started :workflow/phase-completed})

(def terminal-statuses
  "Workflow statuses that indicate no further execution."
  #{:completed :failed :stale})

(def dependency-event-types
  "Event types that carry canonical dependency-health projections."
  #{:dependency/health-updated :dependency/recovered})

(def dependency-status-priority
  "Sort dependency issues from most urgent to least urgent."
  {:operator-action-required 0
   :misconfigured 1
   :unavailable 2
   :degraded 3
   :healthy 4})

(def dependency-active-statuses
  "Statuses that represent dependency trouble rather than healthy state."
  #{:operator-action-required :misconfigured :unavailable :degraded})

(def dependency-status-severity
  "Severity labels used to summarize dependency health in workflow state."
  {:operator-action-required :error
   :misconfigured :error
   :unavailable :error
   :degraded :warning
   :healthy :success})

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
               (filter (comp phase-lifecycle-types :event/type))
               (sort-by #(ts-epoch-ms (event-ts %)))
               last
               event-phase)
      :unknown))

(defn wf-status
  "Derive a workflow status keyword from terminal events and staleness."
  [completed failed last-event-ts]
  (cond
    failed     :failed
    completed  (let [status (get completed :workflow/status (get completed :status))]
                 (if (#{:success :completed} status) :completed :failed))
    :else      (let [last-ts (normalize-ts last-event-ts)]
                 (if (and last-ts
                          (> (- (System/currentTimeMillis) (.getTime ^java.util.Date last-ts))
                             stale-threshold-ms))
                   :stale
                   :running))))

(defn workflow-progress
  "Progress percentage for a workflow: 100 when terminal, 50 while running."
  [status]
  (if (terminal-statuses status) 100 50))

(defn workflow-recency
  "Epoch millis of the most recent workflow activity, for sorting newest-first."
  [wf]
  (or (some-> (:updated-at wf) .getTime)
      (some-> (:started-at wf) .getTime)
      0))

(defn first-event
  "Return the first event of event-type from events."
  [event-type events]
  (->> events (filter #(= event-type (:event/type %))) first))

(defn last-event
  "Return the last event of event-type from events."
  [event-type events]
  (->> events (filter #(= event-type (:event/type %))) last))

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

(defn- assoc-some
  "Assoc only non-nil key/value pairs."
  [m & kvs]
  (reduce (fn [acc [k v]]
            (if (nil? v)
              acc
              (assoc acc k v)))
          m
          (partition 2 kvs)))

(defn- dependency-id
  [event]
  (or (:dependency/id event)
      (:dependency/vendor event)
      (:dependency/source event)))

(defn- dependency-event?
  [event]
  (contains? dependency-event-types (:event/type event)))

(defn- dependency-status-rank
  [status]
  (get dependency-status-priority status Long/MAX_VALUE))

(defn- dependency-entity
  [event]
  (let [dependency-id' (dependency-id event)]
    (assoc-some {:dependency/id dependency-id'
                 :dependency/status (or (:dependency/status event) :healthy)}
                :dependency/source (:dependency/source event)
                :dependency/kind (:dependency/kind event)
                :dependency/vendor (:dependency/vendor event)
                :dependency/class (:dependency/class event)
                :dependency/retryability (:dependency/retryability event)
                :dependency/failure-count (:dependency/failure-count event)
                :dependency/window-size (:dependency/window-size event)
                :dependency/incident-counts (:dependency/incident-counts event)
                :dependency/last-observed-at (:dependency/last-observed-at event)
                :dependency/last-recovered-at (:dependency/last-recovered-at event)
                :dependency/message (:event/message event))))

(defn- workflow-dependency-health
  "Project the latest dependency-health entities from workflow events."
  [events]
  (->> events
       (filter dependency-event?)
       (reduce (fn [health event]
                 (if-let [dependency-id' (dependency-id event)]
                   (assoc health dependency-id' (dependency-entity event))
                   health))
               {})))

(defn- dependency-active?
  [dependency]
  (contains? dependency-active-statuses (:dependency/status dependency)))

(defn- dependency-sort-label
  [dependency]
  (or (:dependency/vendor dependency)
      (some-> (:dependency/id dependency) str)))

(defn- active-dependencies
  [dependency-health]
  (->> (vals dependency-health)
       (filter dependency-active?)
       (sort-by (juxt #(dependency-status-rank (:dependency/status %))
                      dependency-sort-label))
       vec))

(defn- dependency-severity
  [dependency-issues]
  (some-> dependency-issues first :dependency/status dependency-status-severity))

(defn- attach-dependency-health
  [workflow dependency-health]
  (let [dependency-issues (active-dependencies dependency-health)]
    (cond-> workflow
      (seq dependency-health)
      (assoc :dependency-health dependency-health)

      (seq dependency-issues)
      (assoc :dependency-issues dependency-issues
             :dependency-severity (dependency-severity dependency-issues)
             :failure-attribution (first dependency-issues)))))

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
    (let [edn-file? #(str/ends-with? (.getName ^java.io.File %) ".edn")
          events-dir (io/file events-dir-path)]
      (if (.isDirectory events-dir)
        (->> events-dir
             .listFiles
             (filter edn-file?)
             (mapcat read-event-file)
             vec)
        []))))

(defn- matches-workflow? [workflow-id event]
  (or (nil? workflow-id) (workflow-id= workflow-id event)))

(defn- matches-event-type? [event-type event]
  (or (nil? event-type) (= event-type (:event/type event))))

(defn- matches-since? [since-inst event]
  (or (nil? since-inst)
      (when-let [evt-inst (->instant (event-ts event))]
        (not (.isBefore evt-inst since-inst)))))

(defn filter-events
  "Filter and sort events newest-first.

   Options:
   - :workflow-id  Filter by workflow UUID or string id
   - :event-type   Filter by event type keyword
   - :since        Filter events after this timestamp
   - :limit        Max events to return (default 100)"
  [events {:keys [workflow-id event-type since limit] :or {limit 100}}]
  (let [since-inst (when since (->instant since))]
    (->> events
         dedupe-events
         (filter (fn [event]
                   (and (matches-workflow? workflow-id event)
                        (matches-event-type? event-type event)
                        (matches-since? since-inst event))))
         (sort-by #(ts-epoch-ms (event-ts %)) >)
         (take limit)
         vec)))

;------------------------------------------------------------------------------ Layer 1
;; Data fetchers

(def get-workflows
  "Get workflows from live event stream + disk archive (cached 5s).
   Merging both sources ensures workflows running in a separate process
   (e.g. bb miniforge run) are visible in the dashboard."
  (let [ttl-ms 5000
        cache (atom {})]
    (fn [state]
      (let [now (System/currentTimeMillis)
            cached (get @cache [state])]
        (if (and cached (< (- now (:time cached)) ttl-ms))
          (:value cached)
          (let [result (try
                         (let [live-events   (live-stream-events state)
                               live-wf-ids   (into #{} (keep wf-id live-events))
                               ;; Only load disk events for workflows not already in the
                               ;; live stream — avoids contaminating tests and prevents
                               ;; duplicate events for in-process workflows.
                               disk-events   (->> (historical-events nil)
                                                  (remove #(live-wf-ids (wf-id %))))
                               events        (into live-events disk-events)
                               grouped-events (group-by wf-id (filter (comp some? wf-id) events))
                               workflows     (map (fn [[id wf-events]]
                                                    (let [wf-events  (->> wf-events (sort-by #(ts-epoch-ms (event-ts %))) vec)
                                                          started    (first-event :workflow/started   wf-events)
                                                          completed  (last-event  :workflow/completed wf-events)
                                                          failed     (last-event  :workflow/failed    wf-events)
                                                          status     (wf-status completed failed (some-> wf-events last event-ts))
                                                          metrics    (workflow-metrics wf-events)
                                                          output-preview (workflow-output-preview wf-events)
                                                          dependency-health (workflow-dependency-health wf-events)
                                                          workflow-summary (cond-> {:id           id
                                                                                    :name         (wf-name-from-started started id)
                                                                                    :status       status
                                                                                    :phase        (wf-phase wf-events started)
                                                                                    :progress     (workflow-progress status)
                                                                                    :started-at   (normalize-ts (event-ts started))
                                                                                    :updated-at   (normalize-ts (some-> wf-events last event-ts))
                                                                                    :completed-at (normalize-ts (event-ts completed))
                                                                                    :event-count  (count wf-events)
                                                                                    :evidence-bundle-id (:workflow/evidence-bundle-id completed)
                                                                                    :metrics      metrics}
                                                                             output-preview (assoc :latest-output output-preview))]
                                                      (attach-dependency-health workflow-summary dependency-health)))
                                                  grouped-events)]
                           (->> workflows
                                (sort-by workflow-recency >)
                                (take max-recent-workflows)
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
