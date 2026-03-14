(ns ai.miniforge.cli.main.commands.run
  "Run command — execute workflows from spec files, plan files, or DAG files."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.spec-parser :as spec-parser]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.main.commands.plan-executor :as plan-executor]
   [ai.miniforge.cli.main.commands.resume :as resume]))

;------------------------------------------------------------------------------ Layer 0
;; Input type detection

(defn detect-input-type
  "Detect the type of a parsed input file.
   Returns :spec, :dag, :plan, or nil."
  [parsed]
  (cond
    (:spec/title parsed) :spec
    (:dag-id parsed)     :dag
    (:plan/id parsed)    :plan
    :else                nil))

(defn read-edn-file
  "Read an EDN file, returning the parsed data."
  [path]
  (edn/read-string (slurp (str path))))

;------------------------------------------------------------------------------ Layer 1
;; Run command

(defn run-cmd
  "Execute a workflow from a spec, plan, or DAG file."
  [opts]
  (let [{:keys [spec interactive]} opts
        resume-id (:resume opts)]
    (cond
      ;; Resume mode
      resume-id
      (resume/resume-workflow resume-id opts)

      ;; Interactive mode
      interactive
      (do
        (display/print-info (messages/t :run/interactive-not-implemented))
        (println (messages/t :run/interactive-coming-soon)))

      ;; No file specified
      (not spec)
      (display/print-error
       (messages/t :run/usage
                   {:command (app-config/command-string "run <spec-file> [--interactive] [--resume <workflow-id>]")}))

      ;; File not found
      (not (fs/exists? spec))
      (display/print-error (messages/t :run/file-not-found {:path spec}))

      ;; Dispatch based on file content
      :else
      (try
        (let [parsed (read-edn-file spec)
              input-type (detect-input-type parsed)]
          (case input-type
            ;; Spec file — existing workflow path
            :spec
            (do
              (display/print-info (messages/t :run/parsing-spec {:path spec}))
              (let [parsed-spec (spec-parser/parse-spec-file spec)
                    validation (spec-parser/validate-spec parsed-spec)]
                (if-not (:valid? validation)
                  (do
                    (display/print-error (messages/t :run/invalid-spec))
                    (doseq [error (:errors validation)]
                      (println (messages/t :run/validation-error {:error error}))))
                  (do
                    (display/print-info (messages/t :run/running-workflow {:title (:spec/title parsed-spec)}))
                    (workflow-runner/run-workflow-from-spec!
                     parsed-spec
                     (cond-> {:output :pretty :quiet false}
                       (:backend opts) (assoc :backend (:backend opts))))))))

            ;; DAG or Plan file — execute directly
            (:dag :plan)
            (do
              (display/print-info (messages/t :run/detected-format {:format (name input-type) :path spec}))
              (plan-executor/execute-plan parsed opts))

            ;; Unrecognized format
            (display/print-error
             (messages/t :run/unrecognized-format {:path spec}))))
        (catch Exception e
          (let [error-classification (try
                                      (let [classifier (requiring-resolve 'ai.miniforge.agent-runtime.interface/classify-error)]
                                        (when classifier
                                          (classifier e (ex-data e))))
                                      (catch Exception _ nil))]
            (if error-classification
              (display/print-classified-error error-classification)
              (do
                (display/print-error (messages/t :run/failed {:error (ex-message e)}))
                (when-let [data (ex-data e)]
                  (println (messages/t :run/error-details {:details (pr-str data)})))))))))))
