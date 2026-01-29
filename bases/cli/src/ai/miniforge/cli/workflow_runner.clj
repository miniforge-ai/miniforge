(ns ai.miniforge.cli.workflow-runner
  "CLI workflow runner - executes workflows with progress display."
  (:require
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]))

;; Forward declare LLM interface - will be resolved at runtime via requiring-resolve
;; This avoids compile-time dependency on JVM-only code when running in Babashka

;; LLM Backend Adapter
;; ===================
;; Adapts LLM component's LLMClient to Agent component's LLMBackend protocol

(defn create-llm-backend-adapter
  "Create adapter that wraps LLMClient to implement agent's LLMBackend protocol.

   The agent expects: (complete [backend messages opts])
   The LLM provides: (complete* [client request])

   This adapter bridges the two interfaces."
  [llm-client]
  (when llm-client
    (let [complete-fn (requiring-resolve 'ai.miniforge.llm.interface/complete)]
      (reify
        ;; Implement the complete method expected by agents
        clojure.lang.IFn
        (invoke [_this messages opts]
          ;; Convert agent format to LLM format
          (let [request (merge opts {:messages messages})
                result (complete-fn llm-client request)]
            ;; Convert LLM response to agent format
            (if (:success result)
              {:content (:content result)
               :usage (:usage result)
               :model (get-in result [:usage :model] "unknown")}
              {:error (:error result)})))))))

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

(defn- print-workflow-summary
  "Print workflow execution summary with metrics and errors."
  [result]
  (let [status (:execution/status result)
        success? (= status :completed)
        metrics (:execution/metrics result)
        errors (:execution/errors result)]
    (println (if success?
               (colorize :green "✓ Workflow completed")
               (colorize :red "✗ Workflow failed")))
    (when metrics
      (println (str "Tokens: " (:tokens metrics 0)
                   " | Cost: $" (format "%.4f" (:cost-usd metrics 0.0))
                   " | Duration: " (format-duration (:duration-ms metrics 0)))))
    (when (seq errors)
      (println (colorize :red "\nErrors:"))
      (doseq [err errors]
        (println (str "  • " err))))))

(defn- print-pretty-result
  "Print result in pretty format with summary and full data."
  [result]
  (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
  (print-workflow-summary result)
  (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))
  (println "\nFull result:")
  (clojure.pprint/pprint result))

(defn print-result
  "Format and display execution result."
  [result {:keys [output quiet]}]
  (case output
    :json (println (json/generate-string result {:pretty true}))
    :pretty (when-not quiet (print-pretty-result result))
    (clojure.pprint/pprint result)))

;; Workflow Execution
;; ==================

;; Layer 0: Interface resolution

(defn- resolve-workflow-interface
  "Resolve workflow interface functions with helpful error messages."
  []
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
    {:load-workflow load-workflow
     :run-pipeline run-pipeline}))

(defn- create-phase-callbacks
  "Create callbacks for phase start and complete events."
  [quiet]
  {:on-phase-start
   (fn [_ctx interceptor]
     (let [phase-name (get-in interceptor [:config :phase])]
       (when phase-name
         (print-phase-start phase-name quiet))))

   :on-phase-complete
   (fn [_ctx interceptor result]
     (let [phase-name (get-in interceptor [:config :phase])]
       (when phase-name
         (print-phase-complete phase-name result quiet))))})

;; Layer 1: Workflow operations

(defn- load-and-validate-workflow
  "Load workflow and validate it, throwing on errors."
  [load-workflow workflow-id version]
  (let [{:keys [workflow validation]} (load-workflow workflow-id version {})]
    (when-not workflow
      (throw (ex-info (str "Workflow '" workflow-id "' not found. Use 'workflow list' to see available workflows.")
                      {:workflow-id workflow-id :version version})))
    (when (and validation (not (:valid? validation)))
      (throw (ex-info (str "Workflow validation failed: " (:errors validation))
                      {:workflow-id workflow-id :validation validation})))
    workflow))

(defn- create-artifact-store
  "Try to create transit artifact store, return nil if unavailable."
  [quiet]
  (try
    (let [create-transit-store (requiring-resolve 'ai.miniforge.artifact.interface/create-transit-store)]
      (when create-transit-store
        (create-transit-store)))
    (catch Exception _e
      (when-not quiet
        (println (colorize :yellow "Warning: Could not create artifact store, running without persistence")))
      nil)))

(defn- close-artifact-store
  "Close artifact store if it was created."
  [artifact-store]
  (when artifact-store
    (try
      (let [close-store (requiring-resolve 'ai.miniforge.artifact.interface/close-store)]
        (when close-store
          (close-store artifact-store)))
      (catch Exception _))))

;; Layer 2: Execution

(defn- execute-workflow-pipeline
  "Execute workflow pipeline with callbacks and artifact store."
  [run-pipeline workflow input callbacks artifact-store]
  (let [context (cond-> callbacks
                  artifact-store (assoc :artifact-store artifact-store))]
    (run-pipeline workflow input context)))

;; Layer 3: Runtime decoration

(defn- get-git-info
  "Get current git branch and commit. Returns nil if not in git repo or git unavailable."
  []
  (try
    (let [branch-result (p/shell {:out :string :err :string :continue true}
                                  "git" "rev-parse" "--abbrev-ref" "HEAD")
          commit-result (p/shell {:out :string :err :string :continue true}
                                 "git" "rev-parse" "--short" "HEAD")]
      (when (and (zero? (:exit branch-result))
                 (zero? (:exit commit-result)))
        {:git-branch (str/trim (:out branch-result))
         :git-commit (str/trim (:out commit-result))}))
    (catch Exception _ nil)))

(defn- get-files-in-scope
  "Extract files from intent scope, resolving glob patterns."
  [intent]
  (let [scope-paths (get intent :scope [])]
    (vec
     (mapcat
      (fn [path]
        (try
          (if (fs/exists? path)
            (if (fs/directory? path)
              ;; Directory: return as-is, workflow can explore
              [path]
              ;; File: return file path
              [path])
            ;; Might be a glob or doesn't exist yet
            [path])
          (catch Exception _ [path])))
      scope-paths))))

(defn- decorate-spec-with-runtime-context
  "Layer 1 decoration: Add runtime context and metadata to spec.

   Adds:
   - :spec/context   - Runtime environment (cwd, git, files-in-scope)
   - :spec/metadata  - Execution tracking (submitted-at, session-id, iteration)

   This follows the interceptor pattern where each layer adds what it knows."
  [spec {:keys [iteration parent-task-id] :or {iteration 1}}]
  (let [git-info (get-git-info)
        files-in-scope (get-files-in-scope (:spec/intent spec))]
    (assoc spec
           :spec/context
           (cond-> {:cwd (str (fs/cwd))
                    :files-in-scope files-in-scope
                    :environment :development}
             git-info (merge git-info))

           :spec/metadata
           (cond-> {:submitted-at (java.util.Date.)
                    :session-id (random-uuid)
                    :iteration iteration}
             parent-task-id (assoc :parent-task-id parent-task-id)))))

;; Layer 4: Orchestration

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
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)]
      (print-workflow-header workflow-id version quiet)
      (let [workflow-input (resolve-input {:input input :input-json input-json})
            workflow (load-and-validate-workflow load-workflow workflow-id version)
            artifact-store (create-artifact-store quiet)
            callbacks (create-phase-callbacks quiet)
            result (execute-workflow-pipeline run-pipeline workflow workflow-input callbacks artifact-store)]
        (close-artifact-store artifact-store)
        (print-result result opts)
        result))
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

(defn- parse-workflow-filename
  "Parse workflow filename into id and version.
   Returns [id version] or nil if invalid."
  [filename]
  (when-let [[_ id version] (re-matches #"(.+)-v(\d+(?:\.\d+\.\d+)?).edn" filename)]
    [(keyword id) version]))

(defn- load-workflow-metadata
  "Load metadata for a single workflow from resources.
   Returns workflow metadata map or nil on error."
  [filename]
  (try
    (when-let [[id version] (parse-workflow-filename filename)]
      (let [resource-path (str "workflows/" filename)]
        (if-let [resource (io/resource resource-path)]
          (let [content (edn/read-string (slurp resource))]
            {:id id
             :version version
             :description (or (:workflow/description content)
                            (:description content)
                            "No description available")})
          (do
            (println (colorize :yellow (str "Warning: Resource not found: " resource-path)))
            nil))))
    (catch Exception e
      (println (colorize :yellow (str "Warning: Failed to read " filename ": " (ex-message e))))
      nil)))

(defn- format-workflow-listing
  "Format and print workflow listing."
  [workflows]
  (if (empty? workflows)
    (println "No workflows found.")
    (do
      (println (colorize :cyan "\nAvailable Workflows:"))
      (println (colorize :cyan (apply str (repeat 50 "─"))))
      (doseq [{:keys [id version description]} workflows]
        (println (str (colorize :bold (str "  " (name id)))
                     " (v" version ")"
                     (when description (str "\n    " description))))
        (println))
      (println (colorize :cyan (apply str (repeat 50 "─")))))))

(defn- list-workflows-from-resources
  "Lightweight workflow listing that reads directly from resources.
   Used as fallback when full workflow component isn't available."
  []
  (try
    (let [workflow-files ["simple-v2.0.0.edn"
                          "simple-test-v1.0.0.edn"
                          "minimal-test-v1.0.0.edn"
                          "quick-fix-v2.0.0.edn"
                          "lean-sdlc-v1.0.0.edn"
                          "standard-sdlc-v2.0.0.edn"
                          "canonical-sdlc-v1.0.0.edn"]
          workflows (->> workflow-files
                         (map load-workflow-metadata)
                         (remove nil?))]
      (format-workflow-listing workflows))
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

(defn run-workflow-from-spec!
  "Execute a workflow from an arbitrary spec (not from catalog).

   Arguments:
   - spec - Normalized spec map from spec-parser
            {:spec/title \"...\"
             :spec/description \"...\"
             :spec/intent {...}
             :spec/constraints [...]}
   - opts - Execution options
            {:output :pretty|:json|:edn
             :quiet boolean}

   This function bridges user-provided spec files to the workflow engine.
   It creates an ad-hoc workflow definition and executes it."
  [spec {:keys [quiet] :or {quiet false} :as opts}]
  (try
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)]

      (when-not quiet
        (print-workflow-header
         (keyword (str "adhoc-" (hash spec)))
         "adhoc"
         quiet))

      ;; Load workflow specified in spec, or create inline for special cases
      (let [workflow-type (or (:spec/workflow-type spec) :simple)
            workflow-version (or (:spec/workflow-version spec) "latest")
            ;; Try to load workflow, but create inline test-only if it fails and type is :test-only
            workflow (try
                       (load-and-validate-workflow
                        load-workflow
                        workflow-type
                        workflow-version)
                       (catch Exception e
                         (if (= workflow-type :test-only)
                           ;; Create inline test-only workflow (verify -> done)
                           {:workflow/id :test-only
                            :workflow/version "inline"
                            :workflow/name "Test Generation"
                            :workflow/pipeline [{:phase :verify} {:phase :done}]
                            :workflow/config {:max-tokens 20000 :max-iterations 10}}
                           ;; Re-throw for other workflow types
                           (throw e))))

            ;; Layer 1 decoration: Add runtime context and metadata
            enriched-spec (decorate-spec-with-runtime-context spec opts)

            ;; Convert enriched spec to workflow input
            ;; The workflow engine receives the full decorated spec
            ;; Merge in raw spec data to pass through any custom fields (task/*, etc.)
            workflow-input (merge
                            (:spec/raw-data enriched-spec)
                            {:title (:spec/title enriched-spec)
                             :description (:spec/description enriched-spec)
                             :intent (:spec/intent enriched-spec)
                             :constraints (:spec/constraints enriched-spec)
                             :context (:spec/context enriched-spec)
                             :metadata (:spec/metadata enriched-spec)
                             :provenance (:spec/provenance enriched-spec)})

            artifact-store (create-artifact-store quiet)
            callbacks (create-phase-callbacks quiet)

            ;; Create LLM client for agent execution
            ;; Agents use llm/chat from ai.miniforge.llm.interface directly
            llm-client (try
                         (when-let [create-client (requiring-resolve 'ai.miniforge.llm.interface/create-client)]
                           (create-client {:backend :claude}))
                         (catch Exception e
                           (when-not quiet
                             (println (colorize :yellow (str "Warning: Could not create LLM client (" (ex-message e) "), agents will use fallback mode"))))
                           nil))

            ;; Add LLM client to context (agents expect it as :llm-backend)
            context-with-llm (cond-> callbacks
                               llm-client (assoc :llm-backend llm-client)
                               artifact-store (assoc :artifact-store artifact-store))

            result (execute-workflow-pipeline
                    run-pipeline
                    workflow
                    workflow-input
                    context-with-llm
                    artifact-store)]

        (close-artifact-store artifact-store)
        (print-result result opts)
        result))
    (catch Exception e
      (when-not quiet
        (println (colorize :red (str "\n❌ Workflow execution failed: " (ex-message e)))))
      (throw e))))
