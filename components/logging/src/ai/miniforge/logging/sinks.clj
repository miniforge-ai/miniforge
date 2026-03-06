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

(ns ai.miniforge.logging.sinks
  "Configurable log sinks for different deployment scenarios.

   Supported sinks:
   - :file   - Write to ~/.miniforge/logs/<workflow-id>.log (local dev)
   - :stdout - Print to stdout (container/Docker/K8s)
   - :stderr - Print to stderr (error/warn only)
   - :fleet  - Send to fleet command (org-level ops)
   - :multi  - Combine multiple sinks"
  (:require
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.logging.core :as core]))

;;------------------------------------------------------------------------------ Layer 0: File Sink

(defn log-file-path
  "Get path to log file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to ~/.miniforge/logs/<workflow-id>.log"
  [workflow-id]
  (let [home (System/getProperty "user.home")
        logs-dir (io/file home ".miniforge" "logs")
        log-file (str workflow-id ".log")]
    (.mkdirs logs-dir)
    (.getPath (io/file logs-dir log-file))))

(defn file-size-mb [file-path]
  (let [file (io/file file-path)]
    (if (.exists file)
      (/ (.length file) 1024.0 1024.0)
      0.0)))

(defn rotate-log-if-needed [file-path max-size-mb]
  (when (> (file-size-mb file-path) max-size-mb)
    (let [timestamp (.format (java.time.LocalDateTime/now)
                             (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd-HHmmss"))
          rotated-path (str file-path "." timestamp)]
      (.renameTo (io/file file-path) (io/file rotated-path)))))

(defn file-sink
  "Create a file sink that writes logs to per-workflow files.

   Arguments:
     opts - Map with optional:
       :base-dir - Base directory (default: ~/.miniforge/logs)
       :max-size-mb - Max file size before rotation (default 10)
       :format - :edn or :human (default :human)

   Returns: Sink function (fn [log-entry] -> nil)"
  [& [opts]]
  (let [max-size-mb (:max-size-mb opts 10)
        format-fn (case (:format opts :human)
                    :edn core/format-edn
                    :human core/format-human
                    core/format-human)]
    (fn [entry]
      (when-let [workflow-id (:workflow/id entry)]
        (try
          (let [file-path (log-file-path workflow-id)]
            (rotate-log-if-needed file-path max-size-mb)
            (with-open [writer (io/writer file-path :append true)]
              (.write writer (format-fn entry))
              (.write writer "\n")))
          (catch Exception _e
            nil))))))

;;------------------------------------------------------------------------------ Layer 1: Stream Sinks

(defn stdout-sink
  "Create a stdout sink that prints logs to standard output.

   Arguments:
     opts - Map with optional:
       :format - :edn or :human (default :human)
       :min-level - Minimum log level (default :trace)

   Returns: Sink function (fn [log-entry] -> nil)"
  [& [opts]]
  (let [format-fn (case (:format opts :human)
                    :edn core/format-edn
                    :human core/format-human
                    core/format-human)
        min-level (:min-level opts :trace)]
    (fn [entry]
      (when (core/level-enabled? min-level (:log/level entry))
        (try
          (println (format-fn entry))
          (catch Exception _e
            nil))))))

(defn stderr-sink
  "Create a stderr sink that prints logs to standard error.

   Arguments:
     opts - Map with optional:
       :format - :edn or :human (default :human)
       :min-level - Minimum log level (default :warn)

   Returns: Sink function (fn [log-entry] -> nil)"
  [& [opts]]
  (let [format-fn (case (:format opts :human)
                    :edn core/format-edn
                    :human core/format-human
                    core/format-human)
        min-level (:min-level opts :warn)]
    (fn [entry]
      (when (core/level-enabled? min-level (:log/level entry))
        (try
          (binding [*out* *err*]
            (println (format-fn entry)))
          (catch Exception _e
            nil))))))

;;------------------------------------------------------------------------------ Layer 2: Fleet Sink

(defn fleet-sink
  "Create a fleet sink that sends logs to fleet command via HTTP.

   Arguments:
     opts - Map with required:
       :url - Fleet command URL
       :api-key - API key for authentication
     Optional:
       :batch-size - Number of logs to batch (default 50)
       :flush-interval-ms - Max time before flushing (default 10000)

   Returns: Sink function (fn [log-entry] -> nil)"
  [opts]
  (let [_url (or (:url opts) (throw (ex-info "Fleet sink requires :url" {})))
        _api-key (:api-key opts)
        batch-size (:batch-size opts 50)
        flush-interval-ms (:flush-interval-ms opts 10000)
        batch-atom (atom [])
        last-flush-atom (atom (System/currentTimeMillis))]

    (letfn [(flush-batch! []
              (let [logs @batch-atom]
                (when (seq logs)
                  (try
                    ;; TODO: Implement HTTP POST to fleet command
                    ;; (http/post (str _url "/logs")
                    ;;            {:headers {"Authorization" (str "Bearer " _api-key)}
                    ;;             :body (json/generate-string {:logs logs})})
                    (reset! batch-atom [])
                    (reset! last-flush-atom (System/currentTimeMillis))
                    (catch Exception _e
                      nil)))))]

      (fn [entry]
        (swap! batch-atom conj entry)
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

   Returns: Combined sink function (fn [log-entry] -> nil)"
  [sinks]
  (fn [entry]
    (doseq [sink sinks]
      (try
        (sink entry)
        (catch Exception _e
          nil)))))

;;------------------------------------------------------------------------------ Layer 4: Sink Factory

(defn create-sink
  "Create a log sink from configuration.

   Arguments:
     sink-config - Keyword or map:
       :file - File sink
       :stdout - Stdout sink
       :stderr - Stderr sink (warn/error only)
       {:type :fleet :url \"...\" :api-key \"...\"} - Fleet sink
       {:type :multi :sinks [...]} - Multi-sink

   Returns: Sink function (fn [log-entry] -> nil)"
  [sink-config]
  (cond
    ;; Keyword shortcuts
    (= sink-config :file) (file-sink)
    (= sink-config :stdout) (stdout-sink)
    (= sink-config :stderr) (stderr-sink)

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
  "Create log sinks from user configuration.

   Arguments:
     config - Config map with :observability {:log-sinks [...]}

   Returns: Output function that combines all sinks

   Example config:
     {:observability {:log-sinks [:file :stdout]
                      :fleet {:url \"https://fleet.miniforge.ai\"
                              :api-key \"key123\"}}}

   Environment variables:
     MINIFORGE_LOG_SINKS=file,stdout
     MINIFORGE_FLEET_URL=https://fleet.miniforge.ai
     MINIFORGE_FLEET_API_KEY=key123"
  [config]
  (let [;; Get sinks from config or env var
        sinks-config (or (get-in config [:observability :log-sinks])
                        (when-let [env (System/getenv "MINIFORGE_LOG_SINKS")]
                          (mapv keyword (str/split env #",")))
                        [:file]) ;; Default to file sink

        ;; Get fleet config
        fleet-config (or (get-in config [:observability :fleet])
                        (when (System/getenv "MINIFORGE_FLEET_URL")
                          {:url (System/getenv "MINIFORGE_FLEET_URL")
                           :api-key (System/getenv "MINIFORGE_FLEET_API_KEY")}))

        ;; Replace :fleet keyword with full fleet config
        sinks-config (mapv (fn [sink]
                            (if (= sink :fleet)
                              (assoc fleet-config :type :fleet)
                              sink))
                          sinks-config)

        ;; Create all sinks
        sinks (mapv create-sink sinks-config)]

    ;; Return combined output function
    (multi-sink sinks)))
