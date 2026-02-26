(ns ai.miniforge.cli.main.commands.monitoring
  "Web dashboard and TUI monitoring commands."
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.config :as config]))

;------------------------------------------------------------------------------ Layer 1
;; Web dashboard command

(defn web-cmd
  "Start web dashboard for workflow monitoring."
  [opts]
  (let [{:keys [port open]} opts
        port (or port
                 (get-in (config/load-config) [:dashboard :port])
                 7878)]
    (try
      ;; Conditionally require web-dashboard (may not be on classpath in Babashka)
      (require '[ai.miniforge.web-dashboard.interface :as dashboard])
      (require '[ai.miniforge.event-stream.interface :as es])

      (let [dashboard-ns (find-ns 'ai.miniforge.web-dashboard.interface)
            es-ns (find-ns 'ai.miniforge.event-stream.interface)]
        (when-not (and dashboard-ns es-ns)
          (display/print-error "Web dashboard not available in this runtime.")
          (println "The dashboard requires JVM components not available in Babashka.")
          (System/exit 1))

        (let [start! (ns-resolve dashboard-ns 'start!)
              create-stream (ns-resolve es-ns 'create-event-stream)
              event-stream (create-stream)

              ;; Create PR train manager
              pr-train-manager (try
                                 (require '[ai.miniforge.pr-train.interface :as pr-train])
                                 (when-let [pr-ns (find-ns 'ai.miniforge.pr-train.interface)]
                                   (when-let [create-fn (ns-resolve pr-ns 'create-manager)]
                                     (create-fn)))
                                 (catch Exception e
                                   (println "Warning: Could not create PR train manager:" (.getMessage e))
                                   nil))

              ;; Create repo DAG manager
              repo-dag-manager (try
                                 (require '[ai.miniforge.repo-dag.interface :as repo-dag])
                                 (when-let [dag-ns (find-ns 'ai.miniforge.repo-dag.interface)]
                                   (when-let [create-fn (ns-resolve dag-ns 'create-manager)]
                                     (create-fn)))
                                 (catch Exception e
                                   (println "Warning: Could not create repo DAG manager:" (.getMessage e))
                                   nil))

              url (str "http://localhost:" port)]
          (display/print-info (str "Starting web dashboard on port " port "..."))
          (start! {:port port
                   :event-stream event-stream
                   :pr-train-manager pr-train-manager
                   :repo-dag-manager repo-dag-manager})
          (println)
          (println (str "  " url))
          (println)
          (if open
            (do
              (println "Opening browser...")
              (try
                (process/sh "open" url)
                (catch Exception _e nil))
              (println "Press Ctrl+C to stop")
              @(promise))
            (do
              (println "Press Enter to open in browser, Ctrl+C to stop")
              (read-line)
              (println "Opening browser...")
              (try
                (process/sh "open" url)
                (catch Exception _e nil))
              @(promise)))))  ; Block until interrupted
      (catch java.net.BindException _e
        (display/print-error (str "Port " port " is already in use."))
        (println)
        (println "Solutions:")
        (println (str "  1. Use a different port: bb miniforge web --port " (inc port)))
        (println (str "  2. Kill the process using port " port ":"))
        (println (str "     lsof -ti:" port " | xargs kill"))
        (System/exit 1))
      (catch Exception e
        (display/print-error (str "Failed to start web dashboard: " (ex-message e)))
        (System/exit 1)))))

;; TUI availability check (set at load time by parent ns)
(def ^:dynamic *tui-available?* false)

(defn tui-cmd
  "Start terminal UI for workflow monitoring.
   Launches standalone TUI that tail-follows ~/.miniforge/events/ files."
  [opts]
  (if-not *tui-available?*
    (do
      (display/print-error "TUI not available in this runtime.")
      (println)
      (println "The TUI requires JVM/Lanterna which isn't available in Babashka.")
      (println)
      (println "To use the terminal UI, install the separate package:")
      (println "  brew install miniforge-tui")
      (println)
      (println "Or use the web dashboard instead:")
      (println "  miniforge web")
      (System/exit 1))
    (do
      (display/print-info "Starting TUI monitor...")
      (display/print-info "Watching ~/.miniforge/events/ for workflow activity")
      (display/print-info "Press 'q' to quit, '?' for help")
      (println)
      (try
        (let [start-standalone! (requiring-resolve 'ai.miniforge.tui-views.interface/start-standalone-tui!)]
          ;; Start standalone TUI (blocks until quit)
          (start-standalone! opts))
        (catch Exception e
          (display/print-error (str "Failed to start TUI: " (.getMessage e)))
          (when (str/includes? (str e) "terminal")
            (println)
            (println "Note: The TUI requires a proper terminal environment.")
            (println "Try running from Terminal.app or iTerm2, not from an IDE.")))))))
