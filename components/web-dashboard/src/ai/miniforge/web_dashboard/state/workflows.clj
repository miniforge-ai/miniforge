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
  "Workflow state from event stream.")

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(defn- normalize-ts
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

(defn- wf-id
  [event]
  (or (:workflow/id event) (:workflow-id event)))

(defn- wf-name-from-started
  [started id]
  (or (get-in started [:workflow/spec :title])
      (get-in started [:workflow/spec :name])
      (get-in started [:workflow-spec :title])
      (get-in started [:workflow-spec :name])
      (get-in started [:spec :title])
      (get-in started [:spec :name])
      (str "Workflow " (subs (str id) 0 (min 8 (count (str id)))))))

(defn- wf-phase
  [events started]
  (or (:workflow/phase started)
      (:phase started)
      (some->> events
               (filter #(#{:workflow/phase-started :workflow/phase-completed} (:event/type %)))
               (sort-by #(or (:event/timestamp %) (:timestamp %)))
               last
               ((fn [e] (or (:workflow/phase e) (:phase e)))))
      "unknown"))

(defn- wf-status
  [completed failed last-event-ts]
  (cond
    failed :failed
    completed (case (or (:workflow/status completed) (:status completed))
                :success :completed
                :completed :completed
                :failure :failed
                :failed :failed
                :cancelled :failed
                :completed)
    ;; No completion/failure event — check staleness (10 min with no events)
    :else (let [stale-threshold-ms (* 10 60 1000)
                last-ts (normalize-ts last-event-ts)
                now (System/currentTimeMillis)]
            (if (and last-ts (> (- now (.getTime last-ts)) stale-threshold-ms))
              :stale
              :running))))

(def ^:private resolved-get-events
  "Cached reference to event-stream get-events fn."
  (delay
    (try
      (require 'ai.miniforge.event-stream.interface)
      (ns-resolve (find-ns 'ai.miniforge.event-stream.interface) 'get-events)
      (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 1
;; Data fetchers

(def get-workflows
  "Get workflows from event stream (cached 5s)."
  (let [ttl-ms 5000
        cache (atom {})]
    (fn [state]
      (let [now (System/currentTimeMillis)
            cached (get @cache [state])]
        (if (and cached (< (- now (:time cached)) ttl-ms))
          (:value cached)
          (let [result (try
                         (if-let [stream (:event-stream @state)]
                           (if-let [get-events @resolved-get-events]
                             (let [events (get-events stream)]
                               (->> events
                                    (filter #(#{:workflow/started
                                                :workflow/phase-started
                                                :workflow/phase-completed
                                                :workflow/completed
                                                :workflow/failed}
                                              (:event/type %)))
                                    (filter (comp some? wf-id))
                                    (group-by wf-id)
                                    (map (fn [[id wf-events]]
                                           (let [started (first (filter #(= :workflow/started (:event/type %)) wf-events))
                                                 completed (first (filter #(= :workflow/completed (:event/type %)) wf-events))
                                                 failed (first (filter #(= :workflow/failed (:event/type %)) wf-events))
                                                 started-ts (or (:event/timestamp started)
                                                                (:timestamp started))
                                                 completed-ts (or (:event/timestamp completed)
                                                                  (:timestamp completed))
                                                 last-event-ts (->> wf-events
                                                                    (keep #(or (:event/timestamp %) (:timestamp %)))
                                                                    sort
                                                                    last)
                                                 status (wf-status completed failed last-event-ts)]
                                             {:id id
                                              :name (wf-name-from-started started id)
                                              :status status
                                              :phase (wf-phase wf-events started)
                                              :progress (if (#{:completed :failed :stale} status) 100 50)
                                              :started-at (normalize-ts started-ts)
                                              :completed-at (normalize-ts completed-ts)
                                              :evidence-bundle-id (:workflow/evidence-bundle-id completed)})))
                                    (sort-by (fn [wf]
                                               (some-> (:started-at wf) .getTime))
                                             #(compare (or %2 0) (or %1 0)))
                                    (take 50)
                                    vec))
                             [])
                           [])
                         (catch Exception e
                           (println "Error getting workflows:" (.getMessage e))
                           []))]
            (swap! cache assoc [state] {:value result :time now})
            result))))))

(defn get-workflow-detail
  "Get workflow detail."
  [state id]
  (let [workflows (get-workflows state)]
    (or (first (filter #(= (str (:id %)) id) workflows))
        {:error "Workflow not found"})))

(defn get-events
  "Query raw events from event stream with optional filtering.

   Options:
   - :workflow-id  Filter by workflow UUID
   - :event-type   Filter by event type keyword
   - :since        Filter events after this timestamp (ISO-8601 string)
   - :limit        Max events to return (default 100)"
  [state {:keys [workflow-id event-type since limit] :or {limit 100}}]
  (try
    (if-let [stream (:event-stream @state)]
      (if-let [get-events-fn @resolved-get-events]
        (let [all-events (get-events-fn stream)
              since-inst (when since
                           (try (java.time.Instant/parse since)
                                (catch Exception _ nil)))
              filtered (->> all-events
                            (filter (fn [e]
                                      (and (or (nil? workflow-id)
                                               (= workflow-id (wf-id e)))
                                           (or (nil? event-type)
                                               (= event-type (:event/type e)))
                                           (or (nil? since-inst)
                                               (when-let [ts (:event/timestamp e)]
                                                 (let [evt-inst (cond
                                                                  (instance? java.time.Instant ts) ts
                                                                  (string? ts) (try (java.time.Instant/parse ts)
                                                                                    (catch Exception _ nil))
                                                                  :else nil)]
                                                   (and evt-inst (.isAfter evt-inst since-inst))))))))
                            reverse
                            (take limit)
                            vec)]
          filtered)
        [])
      [])
    (catch Exception e
      (println "Error querying events:" (.getMessage e))
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

