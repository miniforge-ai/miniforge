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

(ns ai.miniforge.event-stream.sinks
  "Configurable event sinks for different deployment scenarios.

   Supported sinks:
   - :file   - Write to ~/.miniforge/events/<workflow-id>.edn (local dev)
   - :stdout - Print to stdout (container/Docker/K8s)
   - :stderr - Print to stderr (error-only events)
   - :fleet  - Send to fleet command (org-level ops)
   - :multi  - Combine multiple sinks"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.pprint :as pprint]))

;;------------------------------------------------------------------------------ Layer 0: File Sink

(defn event-file-path
  "Get path to event file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to ~/.miniforge/events/<workflow-id>.edn"
  [workflow-id]
  (let [home (System/getProperty "user.home")
        events-dir (io/file home ".miniforge" "events")
        workflow-file (str workflow-id ".edn")]
    (.mkdirs events-dir)
    (.getPath (io/file events-dir workflow-file))))

(defn operator-event-file-path
  "Get path to the operator event log for cross-workflow events.
   Used by meta-loop, reliability, and degradation events.

   Returns: String path to ~/.miniforge/events/operator.edn"
  []
  (let [home (System/getProperty "user.home")
        events-dir (io/file home ".miniforge" "events")]
    (.mkdirs events-dir)
    (.getPath (io/file events-dir "operator.edn"))))

(defn cleanup-stale-events!
  "Delete event files older than TTL from the events directory.

   Arguments:
     opts - Map with optional:
       :ttl-ms - Max age in milliseconds (default: 7 days)

   Returns: Number of files deleted"
  [& [opts]]
  (let [ttl-ms (get opts :ttl-ms (* 7 24 60 60 1000))
        home (System/getProperty "user.home")
        events-dir (io/file home ".miniforge" "events")
        cutoff (- (System/currentTimeMillis) ttl-ms)]
    (if (.isDirectory events-dir)
      (let [stale-files (->> (.listFiles events-dir)
                             (filter #(and (.isFile %)
                                           (str/ends-with? (.getName %) ".edn")
                                           (< (.lastModified %) cutoff))))]
        (doseq [f stale-files] (.delete f))
        (count stale-files))
      0)))

(defn operator-event-file-path
  "Get path to the operator event log (for cross-workflow events like meta-loop,
   reliability, degradation). Returns ~/.miniforge/events/operator.edn"
  []
  (let [home (System/getProperty "user.home")
        events-dir (io/file home ".miniforge" "events")]
    (.mkdirs events-dir)
    (str (io/file events-dir "operator.edn"))))

(defn file-sink
  "Create a file sink that writes events to per-workflow files.
   Cross-workflow events (no :workflow/id) go to operator.edn.

   Performs lazy cleanup of stale event files (older than 7 days) on creation.

   Arguments:
     opts - Map with optional:
       :base-dir - Base directory (default: ~/.miniforge/events)
       :ttl-ms   - Max event file age in ms (default: 7 days)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  ;; Non-blocking cleanup on sink creation
  (future (try (cleanup-stale-events! opts) (catch Exception _ nil)))
  (fn [event]
    (try
      (let [file-path (if-let [workflow-id (:workflow/id event)]
                        (event-file-path workflow-id)
                        (operator-event-file-path))]
        (with-open [writer (io/writer file-path :append true)]
          (.write writer (pr-str event))
          (.write writer "\n")
          (.flush writer)))
      (catch Exception e
        ;; Log to stderr so failures are visible without breaking the event stream
        (binding [*out* *err*]
          (println (str "WARNING: Event sink write failed: " (ex-message e))))
        nil))))

;;------------------------------------------------------------------------------ Layer 1: Stream Sinks

(defn stdout-sink
  "Create a stdout sink that prints events to standard output.

   Useful for containers where stdout is collected by log aggregators.

   Arguments:
     opts - Map with optional:
       :format - :edn or :json (default :edn)
       :compact - Boolean for compact output (default false)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  (let [format (:format opts :edn)
        compact? (:compact opts false)]
    (fn [event]
      (try
        (let [output (case format
                       :edn (if compact?
                              (pr-str event)
                              (with-out-str (pprint/pprint event)))
                       :json (let [gen-str (requiring-resolve 'cheshire.core/generate-string)]
                               (str (gen-str event {:pretty (not compact?)})))
                       (pr-str event))]
          (println output)
          (flush))
        (catch Exception _e
          nil)))))

(defn stderr-sink
  "Create a stderr sink that prints events to standard error.

   Useful for error/warning events that should go to stderr stream.

   Arguments:
     opts - Map with optional:
       :filter - Predicate (fn [event] -> bool) to filter events
       :format - :edn or :json (default :edn)

   Returns: Sink function (fn [event] -> nil)"
  [& [opts]]
  (let [filter-fn (:filter opts (constantly true))
        format (:format opts :edn)]
    (fn [event]
      (when (filter-fn event)
        (try
          (let [output (case format
                         :edn (pr-str event)
                         :json (let [gen-str (requiring-resolve 'cheshire.core/generate-string)]
                                 (gen-str event))
                         (pr-str event))]
            (binding [*out* *err*]
              (println output)))
          (catch Exception _e
            nil))))))

;;------------------------------------------------------------------------------ Layer 2: Fleet Sink

(defn fleet-sink
  "Create a fleet sink that sends events to fleet command via HTTP.

   Arguments:
     opts - Map with required:
       :url - Fleet command URL (e.g., https://fleet.miniforge.ai)
       :api-key - API key for authentication
     Optional:
       :batch-size - Number of events to batch (default 10)
       :flush-interval-ms - Max time before flushing (default 5000)
       :timeout-ms - HTTP timeout (default 10000)

   Returns: Sink function (fn [event] -> nil)"
  [opts]
  (let [_url (or (:url opts) (throw (ex-info "Fleet sink requires :url" {})))
        _api-key (:api-key opts)
        batch-size (:batch-size opts 10)
        flush-interval-ms (:flush-interval-ms opts 5000)
        _timeout-ms (:timeout-ms opts 10000)
        batch-atom (atom [])
        last-flush-atom (atom (System/currentTimeMillis))]

    (letfn [(flush-batch! []
              (let [events @batch-atom]
                (when (seq events)
                  (try
                    ;; TODO: Implement HTTP POST to fleet command
                    ;; (http/post (str _url "/events")
                    ;;            {:headers {"Authorization" (str "Bearer " _api-key)
                    ;;                       "Content-Type" "application/json"}
                    ;;             :body (json/generate-string {:events events})
                    ;;             :timeout _timeout-ms})
                    (reset! batch-atom [])
                    (reset! last-flush-atom (System/currentTimeMillis))
                    (catch Exception _e
                      ;; Log error but don't fail
                      nil)))))]

      (fn [event]
        (swap! batch-atom conj event)
        (let [now (System/currentTimeMillis)
              time-since-flush (- now @last-flush-atom)
              batch-full? (>= (count @batch-atom) batch-size)
              time-exceeded? (>= time-since-flush flush-interval-ms)]
          (when (or batch-full? time-exceeded?)
            (flush-batch!)))))))

;;------------------------------------------------------------------------------ Layer 3: Multi-Sink

(defn multi-sink
  "Create a multi-sink that writes to multiple sinks simultaneously.

   Arguments:
     sinks - Vector of sink functions

   Returns: Combined sink function (fn [event] -> nil)"
  [sinks]
  (fn [event]
    (doseq [sink sinks]
      (try
        (sink event)
        (catch Exception _e
          ;; Continue with other sinks even if one fails
          nil)))))

;;------------------------------------------------------------------------------ Layer 4: Sink Factory

(defn create-sink
  "Create a sink from configuration.

   Arguments:
     sink-config - Keyword or map:
       :file - File sink
       :stdout - Stdout sink
       :stderr - Stderr sink
       {:type :fleet :url \"...\" :api-key \"...\"} - Fleet sink
       {:type :multi :sinks [...]} - Multi-sink

   Returns: Sink function (fn [event] -> nil)"
  [sink-config]
  (cond
    ;; Keyword shortcuts
    (= sink-config :file) (file-sink)
    (= sink-config :stdout) (stdout-sink)
    (= sink-config :stderr) (stderr-sink {:filter (fn [e]
                                                     (contains? #{:error :workflow/failed}
                                                               (:event/type e)))})

    ;; Map configurations
    (map? sink-config)
    (case (:type sink-config)
      :file (file-sink (dissoc sink-config :type))
      :stdout (stdout-sink (dissoc sink-config :type))
      :stderr (stderr-sink (dissoc sink-config :type))
      :fleet (fleet-sink (dissoc sink-config :type))
      :multi (multi-sink (map create-sink (:sinks sink-config)))
      (throw (ex-info "Unknown sink type" {:type (:type sink-config)})))

    ;; Vector - treat as multi-sink
    (vector? sink-config)
    (multi-sink (map create-sink sink-config))

    :else
    (throw (ex-info "Invalid sink configuration" {:config sink-config}))))

(defn create-sinks-from-config
  "Create sinks from user configuration.

   Arguments:
     config - Config map with :observability {:event-sinks [...]}

   Returns: Vector of sink functions

   Example config:
     {:observability {:event-sinks [:file :stdout]
                      :fleet {:url \"https://fleet.miniforge.ai\"
                              :api-key \"key123\"}}}

   Environment variables:
     MINIFORGE_EVENT_SINKS=file,stdout
     MINIFORGE_FLEET_URL=https://fleet.miniforge.ai
     MINIFORGE_FLEET_API_KEY=key123"
  [config]
  (let [;; Get sinks from config or env var
        sinks-config (or (get-in config [:observability :event-sinks])
                        (when-let [env (System/getenv "MINIFORGE_EVENT_SINKS")]
                          (mapv keyword (str/split env #",")))
                        [:file]) ;; Default to file sink

        ;; Get fleet config from config or env vars
        fleet-config (or (get-in config [:observability :fleet])
                        (when (System/getenv "MINIFORGE_FLEET_URL")
                          {:url (System/getenv "MINIFORGE_FLEET_URL")
                           :api-key (System/getenv "MINIFORGE_FLEET_API_KEY")}))

        ;; Replace :fleet keyword with full fleet config
        sinks-config (mapv (fn [sink]
                            (if (= sink :fleet)
                              (assoc fleet-config :type :fleet)
                              sink))
                          sinks-config)]
    (mapv create-sink sinks-config)))
