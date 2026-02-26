(ns ai.miniforge.cli.main.commands.run
  "Run command — execute workflows from spec files."
  (:require
   [babashka.fs :as fs]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.spec-parser :as spec-parser]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]))

;------------------------------------------------------------------------------ Layer 1
;; TUI mode

(defn- run-with-tui
  "Run workflow with TUI monitoring. Shares event-stream between runner and TUI."
  [parsed-spec opts]
  (let [create-event-stream (requiring-resolve 'ai.miniforge.event-stream.interface/create-event-stream)
        start-tui! (requiring-resolve 'ai.miniforge.tui-views.interface/start-tui!)
        event-stream (create-event-stream)]
    ;; Launch workflow on background thread
    (future
      (try
        (workflow-runner/run-workflow-from-spec!
         parsed-spec
         (assoc opts :event-stream event-stream))
        (catch Exception e
          (println (str "Workflow error: " (ex-message e))))))
    ;; Block on TUI (main thread)
    (start-tui! event-stream)))

;------------------------------------------------------------------------------ Layer 2
;; Run command

(defn run-cmd
  "Execute a workflow from a spec file."
  [opts]
  (let [{:keys [spec interactive tui]} opts]
    (cond
      interactive
      (do
        (display/print-info "Interactive mode not yet implemented")
        (println "Coming soon: conversational workflow execution"))

      (not spec)
      (display/print-error "Usage: miniforge run <spec-file> [--interactive] [--tui]")

      (not (fs/exists? spec))
      (display/print-error (str "Spec file not found: " spec))

      (:tui opts)
      (try
        (display/print-info (str "Parsing workflow spec: " spec))
        (let [parsed-spec (spec-parser/parse-spec-file spec)
              validation (spec-parser/validate-spec parsed-spec)]
          (if-not (:valid? validation)
            (do
              (display/print-error "Invalid workflow spec:")
              (doseq [error (:errors validation)]
                (println (str "  - " error))))
            (do
              (display/print-info (str "Running workflow with TUI monitor: " (:spec/title parsed-spec)))
              (run-with-tui parsed-spec {:output :pretty :quiet true}))))
        (catch Exception e
          (display/print-error (str "Failed to run workflow: " (ex-message e)))))

      :else
      (try
        (display/print-info (str "Parsing workflow spec: " spec))
        (let [parsed-spec (spec-parser/parse-spec-file spec)
              validation (spec-parser/validate-spec parsed-spec)]

          (if-not (:valid? validation)
            (do
              (display/print-error "Invalid workflow spec:")
              (doseq [error (:errors validation)]
                (println (str "  - " error))))

            (do
              (display/print-info (str "Running workflow: " (:spec/title parsed-spec)))
              (workflow-runner/run-workflow-from-spec!
               parsed-spec
               {:output :pretty
                :quiet false}))))
        (catch Exception e
          ;; Try to classify the error if agent-runtime is available
          (let [error-classification (try
                                      (let [classifier (requiring-resolve 'ai.miniforge.agent-runtime.interface/classify-error)]
                                        (when classifier
                                          (classifier e (ex-data e))))
                                      (catch Exception _ nil))]
            (if error-classification
              (display/print-classified-error error-classification)
              ;; Fallback to basic error display
              (do
                (display/print-error (str "Failed to run workflow: " (ex-message e)))
                (when-let [data (ex-data e)]
                  (println (str "  Details: " (pr-str data))))))))))))
