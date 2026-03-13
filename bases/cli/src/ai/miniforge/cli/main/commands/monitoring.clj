(ns ai.miniforge.cli.main.commands.monitoring
  "Web dashboard and TUI monitoring commands."
  (:require
   [babashka.process :as process]
   [clojure.string :as str]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.config :as config]))

;------------------------------------------------------------------------------ Layer 1
;; Web dashboard command

(defn exit!
  [code]
  (System/exit code))

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
          (display/print-error (messages/t :web/not-available))
          (println (messages/t :web/requires-jvm))
          (exit! 1))

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
                                   (println (messages/t :web/pr-train-warning
                                                        {:error (.getMessage e)}))
                                   nil))

              ;; Create repo DAG manager
              repo-dag-manager (try
                                 (require '[ai.miniforge.repo-dag.interface :as repo-dag])
                                 (when-let [dag-ns (find-ns 'ai.miniforge.repo-dag.interface)]
                                   (when-let [create-fn (ns-resolve dag-ns 'create-manager)]
                                     (create-fn)))
                                 (catch Exception e
                                   (println (messages/t :web/repo-dag-warning
                                                        {:error (.getMessage e)}))
                                   nil))

              url (str "http://localhost:" port)]
          (display/print-info (messages/t :web/starting {:port port}))
          (start! {:port port
                   :event-stream event-stream
                   :pr-train-manager pr-train-manager
                   :repo-dag-manager repo-dag-manager})
          (println)
          (println (str "  " url))
          (println)
          (if open
            (do
              (println (messages/t :web/opening-browser))
              (try
                (process/sh "open" url)
                (catch Exception _e nil))
              (println (messages/t :web/press-ctrl-c))
              @(promise))
            (do
              (println (messages/t :web/press-enter))
              (read-line)
              (println (messages/t :web/opening-browser))
              (try
                (process/sh "open" url)
                (catch Exception _e nil))
              @(promise)))))  ; Block until interrupted
      (catch java.net.BindException _e
        (display/print-error (messages/t :web/port-in-use {:port port}))
        (println)
        (println (messages/t :web/solutions))
        (println (messages/t :web/use-different-port
                             {:command (app-config/command-string "web --port"
                                                                  (str (inc port)))}))
        (println (messages/t :web/kill-port-header {:port port}))
        (println (messages/t :web/kill-port-command {:port port}))
        (exit! 1))
      (catch Exception e
        (display/print-error (messages/t :web/start-failed
                                         {:error (ex-message e)}))
        (exit! 1)))))

;; TUI availability check (set at load time by parent ns)
(def ^:dynamic *tui-available?* false)

(defn tui-cmd
  "Start terminal UI for workflow monitoring.
   Launches standalone TUI that tail-follows app event files."
  [opts]
  (if-not *tui-available?*
    (do
      (display/print-error (messages/t :tui/not-available))
      (println)
      (println (messages/t :tui/requires-jvm))
      (println)
      (if-let [tui-package (app-config/tui-package)]
        (do
          (println (messages/t :tui/install-package))
          (println (str "  brew install " tui-package))
          (println)
          (println (messages/t :tui/use-web))
          (println (str "  " (app-config/command-string "web"))))
        (println (messages/t :tui/no-standalone-package
                             {:command (app-config/command-string "web")})))
      (exit! 1))
    (do
      (display/print-info (messages/t :tui/starting))
      (display/print-info (messages/t :tui/watching
                                      {:events-dir (app-config/events-dir)}))
      (display/print-info (messages/t :tui/help-hint))
      (println)
      (try
        (let [start-standalone! (requiring-resolve 'ai.miniforge.tui-views.interface/start-standalone-tui!)]
          ;; Start standalone TUI (blocks until quit)
          (start-standalone! opts)
          ;; Force immediate JVM exit — Clojure's agent thread pool
          ;; keeps the process alive for ~60s otherwise
          (shutdown-agents)
          (exit! 0))
        (catch Exception e
          (display/print-error (messages/t :tui/start-failed
                                           {:error (.getMessage e)}))
          (when (str/includes? (str e) "terminal")
            (println)
            (println (messages/t :tui/proper-terminal-note))
            (println (messages/t :tui/proper-terminal-advice))))))))
