(ns ai.miniforge.cli.workflow-runner.dashboard
  "Dashboard auto-discovery, filesystem command polling, and status display.

   Architecture: Zero network communication between CLI and dashboard.
   - Events (CLI -> Dashboard): CLI writes to ~/.miniforge/events/ via file sink.
     Dashboard tail-follows the files via watcher.
   - Commands (Dashboard -> CLI): Dashboard writes command files to
     ~/.miniforge/commands/<workflow-id>/. CLI polls and deletes them."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [ai.miniforge.cli.workflow-runner.display :as display]))

;------------------------------------------------------------------------------ Layer 0
;; Auto-discovery

(defn discover-dashboard-url
  "Auto-discover running dashboard from ~/.miniforge/dashboard.port.
   Returns dashboard URL string or nil. No health check — just reads the file."
  []
  (try
    (let [discovery-file (str (System/getProperty "user.home") "/.miniforge/dashboard.port")]
      (when (.exists (java.io.File. discovery-file))
        (let [info (json/parse-string (slurp discovery-file) true)
              port (:port info)]
          (str "http://localhost:" port))))
    (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 1
;; Filesystem command polling

(defn start-command-poller!
  "Start a background thread that polls ~/.miniforge/commands/<workflow-id>/
   for .edn command files. Reads command, updates control-state, deletes file.
   Clears stale commands on startup. Returns a cleanup function."
  [workflow-id control-state]
  (let [commands-dir (io/file (System/getProperty "user.home")
                              ".miniforge" "commands" (str workflow-id))
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
                                  :pause (do (swap! control-state assoc :paused true)
                                             (println "\u23f8  Workflow paused by dashboard"))
                                  :resume (do (swap! control-state assoc :paused false)
                                              (println "\u25b6  Workflow resumed by dashboard"))
                                  :stop (do (swap! control-state assoc :stopped true)
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
    (.setName thread "miniforge-command-poller")
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
    (let [events-dir (str (System/getProperty "user.home") "/.miniforge/events")]
      (if-let [url (discover-dashboard-url)]
        (println (display/colorize :cyan (str "   Dashboard: " url)))
        (println (display/colorize :cyan "   Dashboard: not running (start with `miniforge dashboard`)")))
      (println (display/colorize :cyan (str "   Events: " events-dir))))))
