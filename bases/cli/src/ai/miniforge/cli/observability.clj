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

(ns ai.miniforge.cli.observability
  "CLI commands for logs, events, and workflow observability.

   Provides kubectl-style commands with per-workflow streams:
   - logs tail <workflow-id>  - Tail logs for specific workflow
   - logs tail --all          - Tail all workflow logs (aggregated)
   - events tail <workflow-id> - Tail events for specific workflow
   - events tail --all         - Tail all workflow events (aggregated)"
  (:require
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]))

;;------------------------------------------------------------------------------ ANSI Colors

(def ansi-codes
  {:reset "\u001b[0m"
   :bold "\u001b[1m"
   :cyan "\u001b[36m"
   :green "\u001b[32m"
   :yellow "\u001b[33m"
   :red "\u001b[31m"
   :gray "\u001b[90m"
   :blue "\u001b[34m"
   :magenta "\u001b[35m"})

(defn colorize [color text]
  (str (get ansi-codes color "") text (:reset ansi-codes)))

(defn format-timestamp [inst]
  (when inst
    (let [formatter (java.time.format.DateTimeFormatter/ofPattern "HH:mm:ss.SSS")]
      (.format (java.time.LocalDateTime/ofInstant inst (java.time.ZoneId/systemDefault))
               formatter))))

;;------------------------------------------------------------------------------ Layer 0: File Discovery

(defn event-file-path
  "Get path to event file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to the active CLI app events directory."
  [workflow-id]
  (let [events-dir (io/file (app-config/events-dir))
        event-file (str workflow-id ".edn")]
    (.getPath (io/file events-dir event-file))))

(defn log-file-path
  "Get path to log file for a workflow.

   Arguments:
     workflow-id - UUID or string workflow identifier

   Returns: String path to the active CLI app logs directory."
  [workflow-id]
  (let [logs-dir (io/file (app-config/logs-dir))
        log-file (str workflow-id ".log")]
    (.getPath (io/file logs-dir log-file))))

(defn find-log-files
  "Find all log files in the active CLI app logs directory.

   Returns: Vector of file paths sorted by modification time (newest first)"
  []
  (let [log-dir (io/file (app-config/logs-dir))]
    (if (.exists log-dir)
      (->> (.listFiles log-dir)
           (filter #(.isFile %))
           (filter #(.endsWith (.getName %) ".log"))
           (sort-by #(.lastModified %) >)
           (mapv #(.getAbsolutePath %)))
      [])))

(defn find-event-stream-files
  "Find event stream files (workflow execution events).

   Returns: Vector of file paths sorted by modification time (newest first)"
  []
  (let [event-dir (io/file (app-config/events-dir))]
    (if (.exists event-dir)
      (->> (.listFiles event-dir)
           (filter #(.isFile %))
           (filter #(.endsWith (.getName %) ".edn"))
           (sort-by #(.lastModified %) >)
           (mapv #(.getAbsolutePath %)))
      [])))

;;------------------------------------------------------------------------------ Layer 1: Log Parsing

(defn parse-log-line
  "Parse a single log line (EDN format).

   Arguments:
     line - String log line

   Returns: Parsed map or nil if parse fails"
  [line]
  (when-not (str/blank? line)
    (try
      (edn/read-string line)
      (catch Exception _
        nil))))

(defn format-log-entry
  "Format a log entry for display.

   Arguments:
     entry - Parsed log map

   Returns: Formatted string"
  [entry]
  (let [timestamp (format-timestamp (:timestamp entry))
        level (:level entry)
        level-color (case level
                      :error :red
                      :warn :yellow
                      :info :cyan
                      :debug :gray
                      :gray)
        message (:message entry)
        context (:context entry)]
    (str (colorize :gray timestamp)
         " "
         (colorize level-color (str/upper-case (name level)))
         " "
         message
         (when (seq context)
           (str " " (colorize :gray (pr-str context)))))))

;;------------------------------------------------------------------------------ Layer 2: Event Parsing

(defn format-event
  "Format an event for display.

   Arguments:
     event - Event map

   Returns: Formatted string"
  [event]
  (let [timestamp (format-timestamp (:event/timestamp event))
        event-type (:event/type event)
        type-color (case event-type
                     :workflow/started :green
                     :workflow/completed :green
                     :workflow/failed :red
                     :phase/started :cyan
                     :phase/completed :cyan
                     :agent/chunk :gray
                     :error :red
                     :blue)
        workflow-id (:workflow/id event)
        message (:message event)]
    (str (colorize :gray timestamp)
         " "
         (colorize type-color (str "[" (name event-type) "]"))
         " "
         (when workflow-id
           (str (colorize :magenta (str "wf-" (subs (str workflow-id) 0 8))) " "))
         (or message (pr-str (dissoc event :event/timestamp :event/type :workflow/id))))))

;;------------------------------------------------------------------------------ Layer 3: File Tailing

(defn tail-file
  "Tail a file and print each new line.

   Arguments:
     file-path - String path to file
     format-fn - Function to format each line
     lines - Number of initial lines to show (default 10)

   Returns: Never (blocks forever)"
  [file-path format-fn & [{:keys [lines] :or {lines 10}}]]
  (let [file (io/file file-path)
        raf (java.io.RandomAccessFile. file "r")]
    (try
      ;; Show last N lines
      (let [file-length (.length file)
            start-pos (max 0 (- file-length (* lines 200)))] ; Rough estimate
        (.seek raf start-pos)
        (doseq [line (line-seq (io/reader raf))]
          (when-let [formatted (format-fn line)]
            (println formatted))))

      ;; Tail forever
      (loop [last-pos (.length file)]
        (Thread/sleep 500)
        (let [current-length (.length file)]
          (if (> current-length last-pos)
            (do
              (.seek raf last-pos)
              (doseq [line (line-seq (io/reader raf))]
                (when-let [formatted (format-fn line)]
                  (println formatted)))
              (recur (.length file)))
            (recur last-pos))))
      (catch Exception e
        (println (colorize :red (messages/t :observability/error-tailing {:error (.getMessage e)}))))
      (finally
        (.close raf)))))

;;------------------------------------------------------------------------------ Layer 4: Tailing Helpers

(defn show-last-n-lines
  "Show last N lines from a file without following.

   Arguments:
     file-path - Path to file
     parse-fn - Function to parse each line
     format-fn - Function to format parsed entry
     lines - Number of lines to show
     filter-fn - Optional predicate to filter entries"
  [file-path parse-fn format-fn lines & [filter-fn]]
  (let [file (io/file file-path)
        filter-fn (or filter-fn (constantly true))]
    (with-open [rdr (io/reader file)]
      (doseq [line (take-last lines (line-seq rdr))]
        (when-let [entry (parse-fn line)]
          (when (filter-fn entry)
            (println (format-fn entry))))))))

(defn print-stream-header
  "Print header for stream tailing.

   Arguments:
     icon - Icon to show (e.g., 📋 or 📊)
     label - Stream label (e.g., 'logs' or 'events')
     file-path - Path being tailed
     extra-info - Optional extra info to display (e.g., filter)"
  [icon label file-path & [extra-info]]
  (println (colorize :cyan (messages/t :observability/tailing-header {:icon icon :label label :file-path file-path})))
  (when extra-info
    (println (colorize :gray extra-info)))
  (println (colorize :gray (apply str (repeat 80 "─")))))

(defn tail-stream-file
  "Generic file tailing for logs/events.

   Arguments:
     opts - Map with:
       :file-path - Path to file
       :parse-fn - Function to parse line
       :format-fn - Function to format entry
       :filter-fn - Optional predicate
       :lines - Lines to show initially
       :follow - Whether to tail -f
       :icon - Header icon
       :label - Header label
       :extra-info - Extra header info"
  [{:keys [file-path parse-fn format-fn filter-fn lines follow icon label extra-info]
    :or {lines 10 follow true filter-fn (constantly true)}}]
  (if (.exists (io/file file-path))
    (do
      (print-stream-header icon label file-path extra-info)
      (if follow
        (tail-file file-path
                   (fn [line]
                     (when-let [entry (parse-fn line)]
                       (when (filter-fn entry)
                         (format-fn entry))))
                   {:lines lines})
        (show-last-n-lines file-path parse-fn format-fn lines filter-fn)))
    (println (colorize :yellow (messages/t :observability/file-not-found {:file-path file-path})))))

;;------------------------------------------------------------------------------ Layer 5: Stream Tailing

(defn tail-logs
  "Tail MiniForge logs.

   Options:
     :workflow-id - Specific workflow to tail (UUID or string)
     :all - Tail all workflows (aggregated)
     :file - Specific log file to tail (overrides workflow-id)
     :lines - Number of initial lines to show (default: 10)
     :follow - Whether to follow (tail -f) (default: true)"
  [& [{:keys [workflow-id all file lines follow] :or {lines 10 follow true}}]]
  (let [log-files (find-log-files)
        target-file (cond
                      file file
                      workflow-id (log-file-path workflow-id)
                      :else (first log-files))]
    (cond
      ;; Tail all workflows
      all
      (do
        (println (colorize :cyan (messages/t :observability/tailing-all-logs {:icon "📋"})))
        (println (colorize :gray (apply str (repeat 80 "─"))))
        (println (colorize :yellow (messages/t :observability/aggregated-not-implemented))))

      ;; Tail specific workflow
      target-file
      (tail-stream-file {:file-path target-file
                         :parse-fn parse-log-line
                         :format-fn format-log-entry
                         :lines lines
                         :follow follow
                         :icon "📋"
                         :label "logs"})

      ;; No logs found
      :else
      (println (colorize :yellow (messages/t :observability/no-log-files {:dir (app-config/logs-dir)}))))))

(defn tail-events
  "Tail MiniForge workflow events in real-time.

   Options:
     :workflow-id - Specific workflow to tail (UUID or string)
     :all - Tail all workflows (aggregated)
     :file - Specific event file to tail (overrides workflow-id)
     :lines - Number of initial events to show (default: 20)
     :follow - Whether to follow (tail -f) (default: true)
     :filter - Event type filter (e.g., :agent/chunk)"
  [& [{:keys [workflow-id all file lines follow filter] :or {lines 20 follow true}}]]
  (let [event-files (find-event-stream-files)
        target-file (cond
                      file file
                      workflow-id (event-file-path workflow-id)
                      :else (first event-files))
        filter-fn (if filter
                    (fn [event] (= (:event/type event) filter))
                    (constantly true))
        extra-info (when filter (str "Filter: " filter))]
    (cond
      ;; Tail all workflows
      all
      (do
        (println (colorize :cyan (messages/t :observability/tailing-all-events {:icon "📊"})))
        (when filter
          (println (colorize :gray (str "Filter: " filter))))
        (println (colorize :gray (apply str (repeat 80 "─"))))
        (println (colorize :yellow (messages/t :observability/aggregated-not-implemented))))

      ;; Tail specific workflow
      target-file
      (tail-stream-file {:file-path target-file
                         :parse-fn parse-log-line
                         :format-fn format-event
                         :filter-fn filter-fn
                         :lines lines
                         :follow follow
                         :icon "📊"
                         :label "events"
                         :extra-info extra-info})

      ;; No events found
      :else
      (println (colorize :yellow (messages/t :observability/no-event-files {:dir (app-config/events-dir)}))))))

;;------------------------------------------------------------------------------ Layer 6: Command Helpers

(defn list-files-command
  "List files with sizes.

   Arguments:
     find-fn - Function to find files
     label - Label for output (e.g., 'log files')"
  [find-fn label]
  (let [files (find-fn)]
    (if (seq files)
      (do
        (println (colorize :cyan (messages/t :observability/available-files {:label label})))
        (doseq [f files]
          (let [size-mb (/ (.length (io/file f)) 1024.0 1024.0)]
            (println (messages/t :observability/file-entry {:path f :size (format "%.2f" size-mb)})))))
      (println (colorize :yellow (messages/t :observability/no-files-found {:label label}))))))

(defn cat-file-command
  "Display contents of a file.

   Arguments:
     file - File path to display"
  [file]
  (if file
    (println (slurp file))
    (println (colorize :red (messages/t :observability/file-required)))))

;;------------------------------------------------------------------------------ Layer 7: CLI Commands

(defn logs-command
  "Handle 'mf logs' command.

   Subcommands:
     tail [workflow-id] [options]  - Tail logs (default)
     list                          - List available log files
     cat <file>                    - Display log file
     cleanup                       - Clean up old rotated logs"
  [{:keys [subcommand workflow-id file lines follow all] :or {subcommand "tail" lines 10 follow true}}]
  (case subcommand
    "tail" (tail-logs {:workflow-id workflow-id :all all :file file :lines lines :follow follow})
    "list" (list-files-command find-log-files "log files")
    "cat" (cat-file-command file)
    "cleanup" (let [logs-dir (app-config/logs-dir)
                    deleted (requiring-resolve 'ai.miniforge.logging.core/cleanup-old-rotated-logs)]
                (if deleted
                  (let [count (deleted logs-dir 7)]
                    (println (colorize :green (messages/t :observability/cleanup-result {:count count}))))
                  (println (colorize :yellow (messages/t :observability/cleanup-not-available)))))
    (println (colorize :red (messages/t :observability/unknown-subcommand {:subcommand subcommand})))))

(defn events-command
  "Handle 'mf events' command.

   Subcommands:
     tail [workflow-id] [options]  - Tail events (default)
     list                          - List available event files
     cat <file>                    - Display event file"
  [{:keys [subcommand workflow-id file lines follow filter all] :or {subcommand "tail" lines 20 follow true}}]
  (case subcommand
    "tail" (tail-events {:workflow-id workflow-id :all all :file file :lines lines :follow follow :filter filter})
    "list" (list-files-command find-event-stream-files "event files")
    "cat" (cat-file-command file)
    (println (colorize :red (messages/t :observability/unknown-subcommand {:subcommand subcommand})))))

;;------------------------------------------------------------------------------ Public API

(defn handle-logs
  "Entry point for 'mf logs' command."
  [args]
  (logs-command args))

(defn handle-events
  "Entry point for 'mf events' command."
  [args]
  (events-command args))
