(ns ai.miniforge.cli.main.commands.run
  "Run command — execute workflows from spec files, plan files, or DAG files."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.spec-parser :as spec-parser]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.main.commands.plan-executor :as plan-executor]
   [ai.miniforge.cli.main.commands.resume :as resume]))

;------------------------------------------------------------------------------ Layer 0
;; Input type detection

(defn- detect-input-type
  "Detect the type of a parsed input file.
   Returns :spec, :dag, :plan, or nil."
  [parsed]
  (cond
    (:spec/title parsed) :spec
    (:dag-id parsed)     :dag
    (:plan/id parsed)    :plan
    :else                nil))

(defn- read-edn-file
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
        (display/print-info "Interactive mode not yet implemented")
        (println "Coming soon: conversational workflow execution"))

      ;; No file specified
      (not spec)
      (display/print-error "Usage: miniforge run <spec-file> [--interactive] [--resume <workflow-id>]")

      ;; File not found
      (not (fs/exists? spec))
      (display/print-error (str "File not found: " spec))

      ;; Dispatch based on file content
      :else
      (try
        (let [parsed (read-edn-file spec)
              input-type (detect-input-type parsed)]
          (case input-type
            ;; Spec file — existing workflow path
            :spec
            (do
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
                      :quiet false})))))

            ;; DAG or Plan file — execute directly
            (:dag :plan)
            (do
              (display/print-info (str "Detected " (name input-type) " format: " spec))
              (plan-executor/execute-plan parsed opts))

            ;; Unrecognized format
            (display/print-error
             (str "Unrecognized file format in: " spec
                  "\nExpected :spec/title (spec), :dag-id (DAG), or :plan/id (plan)"))))
        (catch Exception e
          (let [error-classification (try
                                      (let [classifier (requiring-resolve 'ai.miniforge.agent-runtime.interface/classify-error)]
                                        (when classifier
                                          (classifier e (ex-data e))))
                                      (catch Exception _ nil))]
            (if error-classification
              (display/print-classified-error error-classification)
              (do
                (display/print-error (str "Failed to run workflow: " (ex-message e)))
                (when-let [data (ex-data e)]
                  (println (str "  Details: " (pr-str data))))))))))))
