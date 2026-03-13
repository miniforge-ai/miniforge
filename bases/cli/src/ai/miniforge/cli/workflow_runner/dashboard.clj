(ns ai.miniforge.cli.workflow-runner.dashboard
  "Dashboard auto-discovery, filesystem command polling, and status display.

   Architecture: Zero network communication between CLI and dashboard.
   - Events (CLI -> Dashboard): CLI writes to the app-configured events directory via file sink.
     Dashboard tail-follows the files via watcher.
   - Commands (Dashboard -> CLI): Dashboard writes command files to
     the app-configured commands directory for the workflow. CLI polls and deletes them."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.workflow-runner.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Auto-discovery

(defn pid-alive?
  "Check if a process with the given PID is still running."
  [pid]
  (try
    (when pid
      (.isPresent (java.lang.ProcessHandle/of (long pid))))
    (catch Exception _ false)))

(defn discover-dashboard-url
  "Auto-discover running dashboard from the app-configured dashboard port file.
   Returns dashboard URL string or nil. Verifies the dashboard PID is alive
   and cleans up stale discovery files from crashed processes."
  []
  (try
    (let [discovery-file (java.io.File. (app-config/dashboard-port-file))]
      (when (.exists discovery-file)
        (let [info (json/parse-string (slurp discovery-file) true)
              port (:port info)
              pid (:pid info)]
          (if (pid-alive? pid)
            (str "http://localhost:" port)
            ;; Stale file from crashed dashboard — clean up
            (do (.delete discovery-file) nil)))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Filesystem command polling

(defn start-command-poller!
  "Start a background thread that polls the app-configured commands directory
   for .edn command files. Reads command, updates control-state, deletes file.
   Clears stale commands on startup. Returns a cleanup function."
  [workflow-id control-state]
  (let [commands-dir (io/file (app-config/commands-dir workflow-id))
        running (atom true)
        ;; Clear stale commands from a previous crashed run
        _ (when (.exists commands-dir)
            (doseq [^java.io.File f (.listFiles commands-dir)]
              (when (.endsWith (.getName f) ".edn")
                (.delete f))))
        thread (Thread.
                (fn []
                  (while @running
                    (try
                      (Thread/sleep 1000)
                      (when (and @running (.exists commands-dir))
                        (let [files (->> (.listFiles commands-dir)
                                         (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
                                         (sort-by #(.getName ^java.io.File %)))]
                          (doseq [^java.io.File file files]
                            (try
                              (let [content (slurp file)
                                    data (edn/read-string content)
                                    command (keyword (:command data))]
                                (case command
                                  :pause (do ((requiring-resolve 'ai.miniforge.event-stream.interface/pause!) control-state)
                                             (println "\u23f8  Workflow paused by dashboard"))
                                  :resume (do ((requiring-resolve 'ai.miniforge.event-stream.interface/resume!) control-state)
                                              (println "\u25b6  Workflow resumed by dashboard"))
                                  :stop (do ((requiring-resolve 'ai.miniforge.event-stream.interface/cancel!) control-state)
                                            (println "\u23f9  Workflow stopped by dashboard"))
                                  nil)
                                (.delete file))
                              (catch Exception _
                                ;; Malformed command file — delete and move on
                                (.delete file))))))
                      (catch InterruptedException _
                        (reset! running false))
                      (catch Exception _
                        nil)))))]
    (.setDaemon thread true)
    (.setName thread (str (app-config/binary-name) "-command-poller"))
    (.start thread)
    (fn []
      (reset! running false)
      (.interrupt thread))))

;------------------------------------------------------------------------------ Layer 2
;; Status display

(defn print-dashboard-status!
  "Print dashboard URL (if discovered) and events directory path."
  [quiet]
  (when-not quiet
    (let [events-dir (app-config/events-dir)]
      (if-let [url (discover-dashboard-url)]
        (println (display/colorize :cyan (str "   Dashboard: " url)))
        (println (display/colorize :cyan
                                   (str "   Dashboard: not running (start with `"
                                        (app-config/command-string "web")
                                        "`)"))))
      (println (display/colorize :cyan (str "   Events: " events-dir))))))
