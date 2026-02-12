(ns ai.miniforge.cli.workflow-runner
  (:require
   [clojure.string :as str]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [babashka.fs :as fs]
   [cheshire.core :as json]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.cli.workflow-recommender :as recommender]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.sandbox :as sandbox]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]))

;------------------------------------------------------------------------------ Layer 0
;; Workflow interface resolution and pipeline helpers

(defn- resolve-workflow-interface []
  (let [load-workflow (try
                        (requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)
                        (catch Exception e
                          (let [msg (ex-message e)
                                data (ex-data e)
                                cause (ex-cause e)]
                            (display/print-error-header msg data cause)
                            (cond
                              (and msg (or (str/includes? msg "could not be resolved")
                                           (str/includes? msg "class not found")
                                           (str/includes? msg "No such namespace")))
                              (display/print-namespace-resolution-help)

                              (and msg (str/includes? msg "Babashka"))
                              (display/print-babashka-fallback-help)

                              :else
                              (display/print-general-debugging-help))
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
       (display/print-phase-start phase-name quiet)))

   :on-phase-complete
   (fn [_ctx interceptor result]
     (when-let [phase-name (get-in interceptor [:config :phase])]
       (display/print-phase-complete phase-name result quiet)))})

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
        (println (display/colorize :yellow "Warning: Could not create artifact store, running without persistence")))
      nil)))

(defn- close-artifact-store [artifact-store]
  (when artifact-store
    (try
      (when-let [close-store (requiring-resolve 'ai.miniforge.artifact.interface/close-store)]
        (close-store artifact-store))
      (catch Exception _))))

(defn- select-workflow-type
  "Select workflow type using LLM recommendation if not explicitly specified."
  [spec llm-client quiet]
  (if-let [explicit-type (:spec/workflow-type spec)]
    (do
      (when-not quiet
        (println (display/colorize :cyan (str "ℹ️  Workflow: " (name explicit-type) " [user-specified]"))))
      explicit-type)
    (let [recommendation (recommender/recommend-workflow-with-fallback spec llm-client)]
      (when-not quiet
        (println (display/colorize :cyan (str "\nℹ️  Workflow Auto-Selected: " (name (:workflow recommendation)))))
        (println (str "   Reason: " (:reasoning recommendation)))
        (when (= :llm (:source recommendation))
          (println (str "   Confidence: " (format "%.0f%%" (* 100 (:confidence recommendation 0.0))))))
        (println (display/colorize :yellow "   Override with :spec/workflow-type in your spec\n")))
      (:workflow recommendation))))

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

(defn- execute-workflow-pipeline [run-pipeline workflow input callbacks artifact-store event-stream]
  (-> callbacks
      (cond-> artifact-store (assoc :artifact-store artifact-store))
      (cond-> event-stream (assoc :event-stream event-stream))
      (->> (run-pipeline workflow input))))

(defn- publish-completion-event [event-stream workflow-id result]
  (let [status (if (= :completed (:execution/status result)) :success :failure)
        duration-ms (get-in result [:execution/metrics :duration-ms])]
    (es/publish! event-stream
                 (if (= status :success)
                   (es/workflow-completed event-stream workflow-id status duration-ms)
                   (es/workflow-failed event-stream workflow-id
                                       {:message (str (first (:execution/errors result)))
                                        :errors (:execution/errors result)})))))

;------------------------------------------------------------------------------ Layer 1
;; Execution orchestration

(defn- execute-with-events [{:keys [run-pipeline workflow workflow-input context artifact-store
                                     event-stream workflow-id sandbox-cleanup opts]}]
  (let [completed? (atom false)]
    (try
      (if-let [sandbox-error (:sandbox-error context)]
        (let [result {:success? false
                      :errors [{:type :sandbox-setup-failed
                                :message (str (:error sandbox-error))}]}]
          (publish-completion-event event-stream workflow-id result)
          (reset! completed? true)
          (display/print-result result opts)
          result)
        (let [result (execute-workflow-pipeline run-pipeline workflow workflow-input context artifact-store event-stream)]
          (publish-completion-event event-stream workflow-id result)
          (reset! completed? true)
          (close-artifact-store artifact-store)
          (display/print-result result opts)
          result))
      (catch Exception e
        (when-not @completed?
          (try
            (es/publish! event-stream
                         (es/workflow-failed event-stream workflow-id
                                             {:message (str "Workflow stopped: " (ex-message e))
                                              :errors [{:type :interrupted :message (ex-message e)}]}))
            (reset! completed? true)
            (catch Exception _ nil)))
        (throw e))
      (finally
        ;; Publish cancelled event if workflow was interrupted without completion
        (when-not @completed?
          (try
            (es/publish! event-stream
                         (es/workflow-failed event-stream workflow-id
                                             {:message "Workflow cancelled"
                                              :errors [{:type :cancelled :message "Process terminated"}]}))
            (catch Exception _ nil)))
        (when sandbox-cleanup
          (sandbox-cleanup)
          (when-not (:quiet opts)
            (println (display/colorize :yellow "🐳 Sandbox container released"))))))))

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
      (display/print-workflow-header workflow-id version quiet)
      (let [workflow-input (context/resolve-input opts)
            workflow (load-and-validate-workflow load-workflow workflow-id version)
            artifact-store (create-artifact-store quiet)
            callbacks (create-phase-callbacks quiet)
            ;; Pass dashboard-url in callbacks if provided
            callbacks-with-url (cond-> callbacks
                                 dashboard-url (assoc :dashboard-url dashboard-url))
            result (execute-workflow-pipeline run-pipeline workflow workflow-input callbacks-with-url artifact-store es)]
        (close-artifact-store artifact-store)
        (display/print-result result opts)
        result))
    (catch Exception e
      (when-not quiet
        (println (display/colorize :red (str "\n✗ Error: " (ex-message e)))))
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
      (println (display/colorize :yellow (str "Warning: Failed to read " filename ": " (ex-message e))))
      nil)))

(defn- format-workflow-listing [workflows]
  (if (empty? workflows)
    (println "No workflows found.")
    (do
      (println (display/colorize :cyan "\nAvailable Workflows:"))
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (doseq [{:keys [id version description]} workflows]
        (println (str (display/colorize :bold (str "  " (name id)))
                      " (v" version ")"
                      "  " (display/colorize :yellow (str ":workflow/type :" (name id)))
                      (when description (str "\n    " description))))
        (println))
      (println (display/colorize :cyan (apply str (repeat 60 "─")))))))

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
      (println (display/colorize :red (str "Failed to list workflows: " (ex-message e)))))))

(defn list-workflows! []
  (try
    (list-workflows-from-resources)
    (catch Exception e
      (println (display/colorize :red (str "Failed to list workflows: " (ex-message e))))
      (throw e))))

;------------------------------------------------------------------------------ Layer 2
;; Spec-driven execution

(defn run-workflow-from-spec! [spec {:keys [quiet] :or {quiet false} :as opts}]
  (try
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create initial LLM client for workflow selection
          selection-llm-client (context/create-llm-client nil spec quiet)
          workflow-type (select-workflow-type spec selection-llm-client quiet)
          workflow-version (or (:spec/workflow-version spec) "latest")
          workflow (load-or-create-workflow load-workflow workflow-type workflow-version)
          enriched-spec (context/decorate-spec-with-runtime-context spec opts)
          workflow-input (context/spec->workflow-input enriched-spec)
          artifact-store (create-artifact-store quiet)
          event-stream (es/create-event-stream)
          workflow-id (or (get-in enriched-spec [:spec/metadata :session-id]) (random-uuid))
          ;; Control state for dashboard commands (pause/resume/stop)
          control-state (atom {:paused false :stopped false :adjustments {}})
          command-poller-cleanup (dashboard/start-command-poller! workflow-id control-state)
          ;; Create workflow-specific LLM client for execution
          llm-client (context/create-llm-client workflow spec quiet)
          callbacks (create-phase-callbacks quiet)
          base-context (context/create-workflow-context {:callbacks callbacks
                                                        :artifact-store artifact-store
                                                        :event-stream event-stream
                                                        :workflow-id workflow-id
                                                        :workflow-type workflow-type
                                                        :workflow-version workflow-version
                                                        :llm-client llm-client
                                                        :quiet quiet
                                                        :spec-title (:spec/title spec)
                                                        :control-state control-state
                                                        :skip-lifecycle-events true})
          sandbox? (or (:sandbox opts) (get-in spec [:spec/raw-data :sandbox]))
          [context sandbox-cleanup] (sandbox/setup-sandbox-context base-context sandbox? spec enriched-spec quiet)]
      (when-not quiet
        (display/print-workflow-header (keyword (str "adhoc-" (hash spec))) "adhoc" quiet))
      (dashboard/print-dashboard-status! quiet)
      (try
        (execute-with-events {:run-pipeline run-pipeline
                              :workflow workflow
                              :workflow-input workflow-input
                              :context context
                              :artifact-store artifact-store
                              :event-stream event-stream
                              :workflow-id workflow-id
                              :sandbox-cleanup sandbox-cleanup
                              :opts opts})
        (finally
          (when command-poller-cleanup (command-poller-cleanup)))))
    (catch Exception e
      (when-not quiet
        (println (display/colorize :red (str "\n❌ Workflow execution failed: " (ex-message e)))))
      (throw e))))
