(ns ai.miniforge.cli.workflow-runner
  (:require
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.pprint]
   [babashka.fs :as fs]
   [babashka.process :as p]
   [cheshire.core :as json]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.cli.workflow-recommender :as recommender]))

(defn create-llm-backend-adapter [llm-client]
  (when llm-client
    (let [complete-fn (requiring-resolve 'ai.miniforge.llm.interface/complete)]
      (reify
        clojure.lang.IFn
        (invoke [_this messages opts]
          (let [request (merge opts {:messages messages})
                result (complete-fn llm-client request)]
            (if (:success result)
              {:content (:content result)
               :usage (:usage result)
               :model (get-in result [:usage :model] "unknown")}
              {:error (:error result)})))))))

(defn parse-workflow-id [s]
  (when s
    (keyword (str/replace s #"^:" ""))))

(defn read-input-file [path]
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

(defn parse-inline-json [s]
  (when s
    (try
      (json/parse-string s true)
      (catch Exception e
        (throw (ex-info (str "Failed to parse input JSON: " (ex-message e))
                        {:input s} e))))))

(defn resolve-input [{:keys [input input-json]}]
  (cond
    input-json (parse-inline-json input-json)
    input (read-input-file input)
    :else {}))

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

(defn print-workflow-header [workflow-id version quiet?]
  (when-not quiet?
    (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
    (println (colorize :bold "  Miniforge Workflow Runner"))
    (println (str "  Workflow: " (colorize :cyan (name workflow-id))))
    (println (str "  Version:  " (colorize :cyan version)))
    (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))))

(defn print-phase-start [phase-name quiet?]
  (when-not quiet?
    (println (str (colorize :yellow "📋") " Phase: " (colorize :bold phase-name) " starting..."))))

(defn print-phase-complete [phase-name result quiet?]
  (when-not quiet?
    (let [status (or (:phase/status result) (:status result) :completed)
          duration (or (get-in result [:phase/metrics :duration-ms])
                       (get-in result [:metrics :duration-ms]))
          success? (= status :completed)]
      (println (str "  "
                    (if success? (colorize :green "✓") (colorize :red "✗"))
                    " " phase-name " "
                    (if success? "completed" "failed")
                    (when duration (str " (" (format-duration duration) ")")))))))

(defn- print-workflow-summary [result]
  (let [{:execution/keys [status metrics errors]} result
        success? (= status :completed)]
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

(defn- print-pretty-result [result]
  (println (colorize :cyan (str "\n" (apply str (repeat 65 "━")))))
  (print-workflow-summary result)
  (println (colorize :cyan (str (apply str (repeat 65 "━")) "\n")))
  (println "\nFull result:")
  (clojure.pprint/pprint result))

(defn print-result [result {:keys [output quiet]}]
  (case output
    :json (println (json/generate-string result {:pretty true}))
    :pretty (when-not quiet (print-pretty-result result))
    (clojure.pprint/pprint result)))

(defn- print-error-header
  "Print error header with message, details, and cause."
  [msg data cause]
  (println (colorize :red "\n✗ Failed to load workflow interface"))
  (println (str "  Error: " msg))
  (when data
    (println (str "  Details: " (pr-str data))))
  (when cause
    (println (str "  Cause: " (ex-message cause))))
  (println (colorize :yellow "\nPossible causes:"))
  (println "  - Missing dependency in deps.edn: ai.miniforge/workflow")
  (println "  - Namespace compilation error in workflow component")
  (println "  - Circular dependency issue"))

(defn- print-namespace-resolution-help
  "Print help for namespace resolution errors."
  []
  (println (colorize :cyan "\nIf the namespace doesn't exist:"))
  (println "  - Check that ai.miniforge/workflow is in your deps.edn")
  (println "  - Verify the component was built: clojure -M:poly test"))

(defn- print-babashka-fallback-help
  "Print help for Babashka compatibility issues."
  []
  (println (colorize :cyan "\nIf running with Babashka (bb):"))
  (println "  - Try the JVM version: clojure -M:dev -m ai.miniforge.cli.main workflow run <id>"))

(defn- print-general-debugging-help
  "Print general debugging tips."
  []
  (println (colorize :cyan "\nFor debugging:"))
  (println "  - Run with verbose output: bb -e '(requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)'"))

(defn- resolve-workflow-interface []
  (let [load-workflow (try
                        (requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)
                        (catch Exception e
                          (let [msg (ex-message e)
                                data (ex-data e)
                                cause (ex-cause e)]
                            (print-error-header msg data cause)
                            (cond
                              (and msg (or (str/includes? msg "could not be resolved")
                                           (str/includes? msg "class not found")
                                           (str/includes? msg "No such namespace")))
                              (print-namespace-resolution-help)

                              (and msg (str/includes? msg "Babashka"))
                              (print-babashka-fallback-help)

                              :else
                              (print-general-debugging-help))
                            (throw (ex-info "Failed to resolve workflow interface"
                                            {:namespace 'ai.miniforge.workflow.interface
                                             :var 'load-workflow
                                             :original-error msg} e)))))
        run-pipeline (requiring-resolve 'ai.miniforge.workflow.interface/run-pipeline)]
    (when-not load-workflow
      (throw (ex-info "Workflow interface not available" {})))
    {:load-workflow load-workflow
     :run-pipeline run-pipeline}))

(defn- create-phase-callbacks [quiet]
  {:on-phase-start
   (fn [_ctx interceptor]
     (when-let [phase-name (get-in interceptor [:config :phase])]
       (print-phase-start phase-name quiet)))

   :on-phase-complete
   (fn [_ctx interceptor result]
     (when-let [phase-name (get-in interceptor [:config :phase])]
       (print-phase-complete phase-name result quiet)))})

(defn- load-and-validate-workflow [load-workflow workflow-id version]
  (let [{:keys [workflow validation]} (load-workflow workflow-id version {})]
    (when-not workflow
      (throw (ex-info (str "Workflow '" workflow-id "' not found. Use 'workflow list' to see available workflows.")
                      {:workflow-id workflow-id :version version})))
    (when (and validation (not (:valid? validation)))
      (throw (ex-info (str "Workflow validation failed: " (:errors validation))
                      {:workflow-id workflow-id :validation validation})))
    workflow))

(defn- create-artifact-store [quiet]
  (try
    (when-let [create-transit-store (requiring-resolve 'ai.miniforge.artifact.interface/create-transit-store)]
      (create-transit-store))
    (catch Exception _e
      (when-not quiet
        (println (colorize :yellow "Warning: Could not create artifact store, running without persistence")))
      nil)))

(defn- close-artifact-store [artifact-store]
  (when artifact-store
    (try
      (when-let [close-store (requiring-resolve 'ai.miniforge.artifact.interface/close-store)]
        (close-store artifact-store))
      (catch Exception _))))

(defn- execute-workflow-pipeline [run-pipeline workflow input callbacks artifact-store event-stream]
  (-> callbacks
      (cond-> artifact-store (assoc :artifact-store artifact-store))
      (cond-> event-stream (assoc :event-stream event-stream))
      (->> (run-pipeline workflow input))))

(defn- get-git-info []
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

(defn- get-files-in-scope [intent]
  (->> (get intent :scope [])
       (mapcat (fn [path]
                 (try
                   (if (and (fs/exists? path) (not (fs/directory? path)))
                     [path]
                     [path])
                   (catch Exception _ [path]))))
       vec))

(defn- decorate-spec-with-runtime-context [spec {:keys [iteration parent-task-id] :or {iteration 1}}]
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

(defn run-workflow! [workflow-id {:keys [version output quiet event-stream dashboard-url]
                                    :or {version "latest" output :pretty quiet false}
                                    :as opts}]
  (try
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create event stream if not provided (dashboard-url takes precedence)
          es (or event-stream
                 (when-not dashboard-url
                   (try
                     (require '[ai.miniforge.event-stream.interface :as es])
                     (when-let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
                       (when-let [create-fn (ns-resolve es-ns 'create-event-stream)]
                         (create-fn)))
                     (catch Exception _ nil))))]
      (print-workflow-header workflow-id version quiet)
      (let [workflow-input (resolve-input opts)
            workflow (load-and-validate-workflow load-workflow workflow-id version)
            artifact-store (create-artifact-store quiet)
            callbacks (create-phase-callbacks quiet)
            ;; Pass dashboard-url in callbacks if provided
            callbacks-with-url (cond-> callbacks
                                 dashboard-url (assoc :dashboard-url dashboard-url))
            result (execute-workflow-pipeline run-pipeline workflow workflow-input callbacks-with-url artifact-store es)]
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

(defn- parse-workflow-filename [filename]
  (when-let [[_ id version] (re-matches #"(.+)-v(\d+(?:\.\d+\.\d+)?).edn" filename)]
    [(keyword id) version]))

(defn- load-workflow-metadata [filename]
  (try
    (when-let [[id version] (parse-workflow-filename filename)]
      (when-let [resource (io/resource (str "workflows/" filename))]
        (let [content (edn/read-string (slurp resource))]
          {:id id
           :version version
           :description (or (:workflow/description content)
                            (:description content)
                            "No description available")})))
    (catch Exception e
      (println (colorize :yellow (str "Warning: Failed to read " filename ": " (ex-message e))))
      nil)))

(defn- format-workflow-listing [workflows]
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

(defn- list-workflows-from-resources []
  (try
    (->> ["simple-v2.0.0.edn"
          "simple-test-v1.0.0.edn"
          "minimal-test-v1.0.0.edn"
          "quick-fix-v2.0.0.edn"
          "lean-sdlc-v1.0.0.edn"
          "standard-sdlc-v2.0.0.edn"
          "canonical-sdlc-v1.0.0.edn"]
         (keep load-workflow-metadata)
         format-workflow-listing)
    (catch Exception e
      (println (colorize :red (str "Failed to list workflows: " (ex-message e)))))))

(defn list-workflows! []
  (try
    (list-workflows-from-resources)
    (catch Exception e
      (println (colorize :red (str "Failed to list workflows: " (ex-message e))))
      (throw e))))

(defn- sandbox-release-fn [executor environment-id]
  (fn []
    (try
      (dag/release-environment! executor environment-id)
      (catch Exception _ nil))))

(defn- infer-repo-url [spec enriched-spec]
  (or (get-in spec [:spec/raw-data :repo-url])
      (get-in enriched-spec [:spec/context :repo-url])
      (try
        (str/trim (:out (p/shell {:out :string :err :string :continue true}
                                 "git" "remote" "get-url" "origin")))
        (catch Exception _ nil))))

(defn- infer-branch [spec enriched-spec]
  (or (get-in spec [:spec/raw-data :branch])
      (get-in enriched-spec [:spec/context :git-branch])
      "main"))

(defn- prepare-sandbox [spec enriched-spec]
  (let [prep-result (dag/prepare-docker-executor! {:image-type :clojure})]
    (if-not (dag/ok? prep-result)
      prep-result
      (let [executor (:executor (dag/unwrap prep-result))
            gh-token (System/getenv "GH_TOKEN")
            env-config (cond-> {} gh-token (assoc :env {:GH_TOKEN gh-token}))
            env-result (dag/acquire-environment! executor (random-uuid) env-config)]
        (if-not (dag/ok? env-result)
          env-result
          (let [env-id (:environment-id (dag/unwrap env-result))
                repo-url (infer-repo-url spec enriched-spec)
                branch (infer-branch spec enriched-spec)]
            (when repo-url
              (dag/clone-and-checkout! executor env-id repo-url branch {}))
            (dag/ok {:executor executor
                     :environment-id env-id
                     :sandbox-workdir "/workspace"})))))))

(defn- setup-sandbox-context [base-context sandbox? spec enriched-spec quiet]
  (if-not sandbox?
    [base-context nil]
    (do
      (when-not quiet
        (println (colorize :yellow "🐳 Setting up sandbox container...")))
      (let [result (prepare-sandbox spec enriched-spec)]
        (if-not (dag/ok? result)
          [(assoc base-context :sandbox-error result) nil]
          (let [{:keys [executor environment-id sandbox-workdir]} (dag/unwrap result)]
            (when-not quiet
              (println (colorize :green "  ✓ Sandbox container ready")))
            [(assoc base-context
                    :executor executor
                    :environment-id environment-id
                    :sandbox-workdir sandbox-workdir)
             (sandbox-release-fn executor environment-id)]))))))

(defn- load-or-create-workflow [load-workflow workflow-type workflow-version]
  (try
    (load-and-validate-workflow load-workflow workflow-type workflow-version)
    (catch Exception e
      (if (= workflow-type :test-only)
        {:workflow/id :test-only
         :workflow/version "inline"
         :workflow/name "Test Generation"
         :workflow/pipeline [{:phase :verify} {:phase :done}]
         :workflow/config {:max-tokens 20000 :max-iterations 10}}
        (throw e)))))

(defn- spec->workflow-input [enriched-spec]
  (merge (:spec/raw-data enriched-spec)
         {:title (:spec/title enriched-spec)
          :description (:spec/description enriched-spec)
          :intent (:spec/intent enriched-spec)
          :constraints (:spec/constraints enriched-spec)
          :context (:spec/context enriched-spec)
          :metadata (:spec/metadata enriched-spec)
          :provenance (:spec/provenance enriched-spec)}))

(defn- create-llm-client [workflow spec quiet]
  (try
    (let [cfg (config/load-config)
          llm-backend (config/get-llm-backend
                       cfg
                       (or (get-in workflow [:workflow/config :llm-backend])
                           (get-in spec [:spec/raw-data :llm-backend])))]
      (when-let [create-client (requiring-resolve 'ai.miniforge.llm.interface/create-client)]
        (create-client {:backend llm-backend})))
    (catch Exception e
      (when-not quiet
        (println (colorize :yellow (str "Warning: Could not create LLM client (" (ex-message e) "), agents will use fallback mode"))))
      nil)))

(defn- create-workflow-context [{:keys [callbacks artifact-store event-stream workflow-id
                                         workflow-type workflow-version llm-client quiet]}]
  (let [on-chunk (es/create-streaming-callback event-stream workflow-id :agent
                                                {:print? (not quiet) :quiet? quiet})]
    (es/publish! event-stream
                 (es/workflow-started event-stream workflow-id
                                      {:name (name workflow-type) :version workflow-version}))
    (cond-> callbacks
      llm-client (assoc :llm-backend llm-client)
      artifact-store (assoc :artifact-store artifact-store)
      on-chunk (assoc :on-chunk on-chunk)
      event-stream (assoc :event-stream event-stream)
      true (assoc :worktree-path (System/getProperty "user.dir")))))

(defn- publish-completion-event [event-stream workflow-id result]
  (let [status (if (= :completed (:execution/status result)) :success :failure)
        duration-ms (get-in result [:execution/metrics :duration-ms])]
    (es/publish! event-stream
                 (if (= status :success)
                   (es/workflow-completed event-stream workflow-id status duration-ms)
                   (es/workflow-failed event-stream workflow-id
                                       {:message (str (first (:execution/errors result)))
                                        :errors (:execution/errors result)})))))

(defn- execute-with-events [{:keys [run-pipeline workflow workflow-input context artifact-store
                                     event-stream workflow-id sandbox-cleanup opts]}]
  (try
    (if-let [sandbox-error (:sandbox-error context)]
      (let [result {:success? false
                    :errors [{:type :sandbox-setup-failed
                              :message (str (:error sandbox-error))}]}]
        (print-result result opts)
        result)
      (let [result (execute-workflow-pipeline run-pipeline workflow workflow-input context artifact-store event-stream)]
        (publish-completion-event event-stream workflow-id result)
        (close-artifact-store artifact-store)
        (print-result result opts)
        result))
    (finally
      (when sandbox-cleanup
        (sandbox-cleanup)
        (when-not (:quiet opts)
          (println (colorize :yellow "🐳 Sandbox container released")))))))

(defn- select-workflow-type
  "Select workflow type using LLM recommendation if not explicitly specified.

   Arguments:
     spec - Task specification map
     llm-client - Optional LLM client for recommendation
     quiet - Boolean to suppress output

   Returns: Keyword workflow type"
  [spec llm-client quiet]
  (if-let [explicit-type (:spec/workflow-type spec)]
    (do
      (when-not quiet
        (println (colorize :cyan (str "ℹ️  Workflow: " (name explicit-type) " [user-specified]"))))
      explicit-type)
    (let [recommendation (recommender/recommend-workflow-with-fallback spec llm-client)]
      (when-not quiet
        (println (colorize :cyan (str "\nℹ️  Workflow Auto-Selected: " (name (:workflow recommendation)))))
        (println (str "   Reason: " (:reasoning recommendation)))
        (when (= :llm (:source recommendation))
          (println (str "   Confidence: " (format "%.0f%%" (* 100 (:confidence recommendation 0.0))))))
        (println (colorize :yellow "   Override with :spec/workflow-type in your spec\n")))
      (:workflow recommendation))))

(defn run-workflow-from-spec! [spec {:keys [quiet] :or {quiet false} :as opts}]
  (try
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create initial LLM client for workflow selection
          selection-llm-client (create-llm-client nil spec quiet)
          workflow-type (select-workflow-type spec selection-llm-client quiet)
          workflow-version (or (:spec/workflow-version spec) "latest")
          workflow (load-or-create-workflow load-workflow workflow-type workflow-version)
          enriched-spec (decorate-spec-with-runtime-context spec opts)
          workflow-input (spec->workflow-input enriched-spec)
          artifact-store (create-artifact-store quiet)
          event-stream (es/create-event-stream)
          workflow-id (or (get-in enriched-spec [:spec/metadata :session-id]) (random-uuid))
          ;; Create workflow-specific LLM client for execution
          llm-client (create-llm-client workflow spec quiet)
          callbacks (create-phase-callbacks quiet)
          base-context (create-workflow-context {:callbacks callbacks
                                                  :artifact-store artifact-store
                                                  :event-stream event-stream
                                                  :workflow-id workflow-id
                                                  :workflow-type workflow-type
                                                  :workflow-version workflow-version
                                                  :llm-client llm-client
                                                  :quiet quiet})
          sandbox? (or (:sandbox opts) (get-in spec [:spec/raw-data :sandbox]))
          [context sandbox-cleanup] (setup-sandbox-context base-context sandbox? spec enriched-spec quiet)]
      (when-not quiet
        (print-workflow-header (keyword (str "adhoc-" (hash spec))) "adhoc" quiet))
      (execute-with-events {:run-pipeline run-pipeline
                            :workflow workflow
                            :workflow-input workflow-input
                            :context context
                            :artifact-store artifact-store
                            :event-stream event-stream
                            :workflow-id workflow-id
                            :sandbox-cleanup sandbox-cleanup
                            :opts opts}))
    (catch Exception e
      (when-not quiet
        (println (colorize :red (str "\n❌ Workflow execution failed: " (ex-message e)))))
      (throw e))))
