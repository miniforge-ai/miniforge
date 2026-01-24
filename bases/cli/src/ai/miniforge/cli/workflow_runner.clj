(ns ai.miniforge.cli.workflow-runner
  "CLI workflow runner - executes workflows with progress display."
  (:require
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint]
   [babashka.fs :as fs]
   [cheshire.core :as json]))

;; Input Processing
;; ================

(defn parse-workflow-id
  "Convert string to keyword, handling ':name' or 'name' formats."
  [s]
  (when s
    (keyword (str/replace s #"^:" ""))))

(defn read-input-file
  "Read EDN or JSON input file based on extension."
  [path]
  (when path
    (let [file (fs/file path)]
      (when-not (fs/exists? file)
        (throw (ex-info (str "Input file not found: " path) {:path path})))
      (let [content (slurp file)
            ext (fs/extension file)]
        (case ext
          "edn" (edn/read-string content)
          "json" (json/parse-string content true)
          (throw (ex-info (str "Unsupported file format: " ext " (use .edn or .json)")
                          {:path path :extension ext})))))))

(defn parse-inline-json
  "Parse inline JSON string."
  [s]
  (when s
    (try
      (json/parse-string s true)
      (catch Exception e
        (throw (ex-info (str "Failed to parse input JSON: " (ex-message e))
                        {:input s} e))))))

(defn resolve-input
  "Resolve input from file or inline JSON, with priority to inline."
  [{:keys [input input-json]}]
  (cond
    input-json (parse-inline-json input-json)
    input (read-input-file input)
    :else {}))

;; Output Formatting
;; =================

(def ^:private ansi-codes
  {:reset "\u001b[0m"
   :bold "\u001b[1m"
   :cyan "\u001b[36m"
   :green "\u001b[32m"
   :yellow "\u001b[33m"
   :red "\u001b[31m"})

(defn- colorize [color text]
  (str (get ansi-codes color "") text (:reset ansi-codes)))

(defn- format-duration [ms]
  (cond
    (< ms 1000) (str ms "ms")
    (< ms 60000) (format "%.1fs" (/ ms 1000.0))
    :else (format "%.1fm" (/ ms 60000.0))))

(defn print-workflow-header
  "Display workflow execution banner."
  [workflow-id version quiet?]
  (when-not quiet?
    (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
    (println (colorize :bold "  Miniforge Workflow Runner"))
    (println (str "  Workflow: " (colorize :cyan (name workflow-id))))
    (println (str "  Version:  " (colorize :cyan version)))
    (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))))

(defn print-phase-start
  "Display phase start indicator."
  [phase-name quiet?]
  (when-not quiet?
    (println (str (colorize :yellow "📋") " Phase: " (colorize :bold phase-name) " starting..."))))

(defn print-phase-complete
  "Display phase completion with status and metrics."
  [phase-name result quiet?]
  (when-not quiet?
    (let [status (or (:phase/status result) (:status result) :completed)
          duration (get-in result [:phase/metrics :duration-ms]
                          (get-in result [:metrics :duration-ms]))
          success? (= status :completed)]
      (println (str "  "
                   (if success?
                     (colorize :green "✓")
                     (colorize :red "✗"))
                   " "
                   phase-name
                   " "
                   (if success? "completed" "failed")
                   (when duration
                     (str " (" (format-duration duration) ")")))))))

(defn print-result
  "Format and display execution result."
  [result {:keys [output quiet]}]
  (case output
    :json
    (println (json/generate-string result {:pretty true}))

    :pretty
    (do
      (when-not quiet
        (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
        (let [status (:execution/status result)
              success? (= status :completed)
              metrics (:execution/metrics result)
              errors (:execution/errors result)]
          (println (str (if success?
                          (colorize :green "✓ Workflow completed")
                          (colorize :red "✗ Workflow failed"))))
          (when metrics
            (println (str "Tokens: " (:tokens metrics 0)
                         " | Cost: $" (format "%.4f" (:cost-usd metrics 0.0))
                         " | Duration: " (format-duration (:duration-ms metrics 0)))))
          (when (seq errors)
            (println (colorize :red "\nErrors:"))
            (doseq [err errors]
              (println (str "  • " err)))))
        (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n"))))

      ;; Also print the full result data structure for inspection
      (when-not quiet
        (println "\nFull result:")
        (clojure.pprint/pprint result)))

    ;; default - just pprint
    (clojure.pprint/pprint result)))

;; Workflow Execution
;; ==================

(defn run-workflow!
  "Main entry point - load, execute, and display workflow results.

  Options:
    :version - Workflow version (default: 'latest')
    :input - Path to EDN/JSON input file
    :input-json - Inline JSON input string
    :output - Output format (:pretty, :json, or :edn)
    :quiet - Suppress progress output (boolean)"
  [workflow-id {:keys [version input input-json output quiet]
                :or {version "latest" output :pretty quiet false}
                :as opts}]
  (try
    ;; Lazy load workflow interface
    (let [load-workflow (try
                          (requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)
                          (catch Exception e
                            (println (colorize :red "\nWorkflow execution requires JVM-only dependencies."))
                            (println (colorize :yellow "This feature is not available in the Babashka CLI build."))
                            (println (colorize :cyan "\nTo run workflows, use the full JVM version:"))
                            (println "  clojure -M:dev -m ai.miniforge.cli.main workflow run <workflow-id>")
                            (throw (ex-info "Workflow execution not available in Babashka build"
                                          {:reason "JVM-only dependencies required"} e))))
          run-pipeline (requiring-resolve 'ai.miniforge.workflow.interface/run-pipeline)]

      (when-not load-workflow
        (throw (ex-info "Workflow interface not available" {})))

      ;; Print header
      (print-workflow-header workflow-id version quiet)

      ;; Resolve input and load workflow
      (let [workflow-input (resolve-input {:input input :input-json input-json})
            {:keys [workflow validation]} (load-workflow workflow-id version {})]

        (when-not workflow
          (throw (ex-info (str "Workflow '" workflow-id "' not found. Use 'workflow list' to see available workflows.")
                          {:workflow-id workflow-id :version version})))

        (when (and validation (not (:valid? validation)))
          (throw (ex-info (str "Workflow validation failed: " (:errors validation))
                          {:workflow-id workflow-id :validation validation})))

        ;; Create artifact store for this workflow execution
        ;; Try to use transit store (BB-compatible), fall back to no store
        (let [artifact-store (try
                               (let [create-transit-store (requiring-resolve 'ai.miniforge.artifact.interface/create-transit-store)]
                                 (when create-transit-store
                                   (create-transit-store)))
                               (catch Exception _e
                                 (when-not quiet
                                   (println (colorize :yellow "Warning: Could not create artifact store, running without persistence")))
                                 nil))
              ;; Execute with callbacks and artifact store
              result (run-pipeline
                          workflow
                          workflow-input
                          (cond-> {:on-phase-start
                                   (fn [_ctx interceptor]
                                     (let [phase-name (get-in interceptor [:config :phase])]
                                       (when phase-name
                                         (print-phase-start phase-name quiet))))

                                   :on-phase-complete
                                   (fn [_ctx interceptor result]
                                     (let [phase-name (get-in interceptor [:config :phase])]
                                       (when phase-name
                                         (print-phase-complete phase-name result quiet))))}

                            ;; Add artifact store if available
                            artifact-store (assoc :artifact-store artifact-store)))]

          ;; Close artifact store if it was created
          (when artifact-store
            (try
              (let [close-store (requiring-resolve 'ai.miniforge.artifact.interface/close-store)]
                (when close-store
                  (close-store artifact-store)))
              (catch Exception _)))

          ;; Print result
          (print-result result opts)

          ;; Return result for programmatic use
          result)))

    (catch Exception e
      (when-not quiet
        (println (colorize :red (str "\n✗ Error: " (ex-message e)))))
      (when (= output :json)
        (println (json/generate-string
                  {:status "error"
                   :error (ex-message e)
                   :data (ex-data e)}
                  {:pretty true})))
      (throw e))))

(defn- list-workflows-from-resources
  "Lightweight workflow listing that reads directly from resources.
   Used as fallback when full workflow component isn't available."
  []
  (try
    ;; List of known workflow files (hardcoded since we can't easily scan resources in jar)
    (let [workflow-files ["simple-v2.0.0.edn"
                          "simple-test-v1.0.0.edn"
                          "minimal-test-v1.0.0.edn"
                          "quick-fix-v2.0.0.edn"
                          "lean-sdlc-v1.0.0.edn"
                          "standard-sdlc-v2.0.0.edn"
                          "canonical-sdlc-v1.0.0.edn"]
          workflows (for [filename workflow-files
                          :let [;; Parse filename like "simple-v2.0.0.edn" or "simple-test-v1.edn"
                                [_ id version] (re-matches #"(.+)-v(\d+(?:\.\d+\.\d+)?).edn" filename)
                                resource-path (str "workflows/" filename)]
                          :when (and id version)]
                      (try
                        (if-let [resource (io/resource resource-path)]
                          (let [content (edn/read-string (slurp resource))]
                            {:id (keyword id)
                             :version version
                             :description (or (:workflow/description content)
                                            (:description content)
                                            "No description available")})
                          (do
                            (println (colorize :yellow (str "Warning: Resource not found: " resource-path)))
                            nil))
                        (catch Exception e
                          (println (colorize :yellow (str "Warning: Failed to read " filename ": " (ex-message e))))
                          nil)))
          valid-workflows (remove nil? workflows)]
      (if (empty? valid-workflows)
        (println "No workflows found.")
        (do
          (println (colorize :cyan "\nAvailable Workflows:"))
          (println (colorize :cyan (apply str (repeat 50 "─"))))
          (doseq [{:keys [id version description]} valid-workflows]
            (println (str (colorize :bold (str "  " (name id)))
                         " (v" version ")"
                         (when description (str "\n    " description))))
            (println))
          (println (colorize :cyan (apply str (repeat 50 "─")))))))
    (catch Exception e
      (println (colorize :red (str "Failed to list workflows: " (ex-message e)))))))

(defn list-workflows!
  "List available workflows from the workflow registry."
  []
  (try
    ;; Always use the fallback for CLI - the workflow interface's list-workflows
    ;; uses file-seq which doesn't work with jar resources
    (list-workflows-from-resources)

    (catch Exception e
      (println (colorize :red (str "Failed to list workflows: " (ex-message e))))
      (throw e))))
