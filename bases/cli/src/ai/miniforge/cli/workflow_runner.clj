;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.

(ns ai.miniforge.cli.workflow-runner
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [cheshire.core :as json]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-recommender :as recommender]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.sandbox :as sandbox]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.phase.interface :as phase]))

;------------------------------------------------------------------------------ Layer 0
;; Work spec kanban lifecycle

(def ^:private work-dirs
  "Kanban folder structure under work/."
  {:in-progress "work/in-progress"
   :done        "work/done"
   :failed      "work/failed"})

(defn- work-spec?
  "True when the spec provenance points to a file under work/."
  [provenance]
  (when-let [source (:source-file provenance)]
    (str/starts-with? (str source) "work/")))

(defn- move-spec!
  "Move a work spec file to the target kanban folder.
   No-op if the spec isn't under work/ or the file doesn't exist."
  [provenance target-key]
  (when (work-spec? provenance)
    (let [source (str (:source-file provenance))
          target-dir (get work-dirs target-key)]
      (when (and target-dir (fs/exists? source))
        (fs/create-dirs target-dir)
        (let [target (str target-dir "/" (fs/file-name source))]
          (fs/move source target {:replace-existing true})
          target)))))

(defn move-spec-to-in-progress!
  "Move a work spec to in-progress when execution starts."
  [provenance]
  (move-spec! provenance :in-progress))

(defn move-spec-on-completion!
  "Move a work spec to done or failed based on workflow result."
  [provenance result]
  (if (phase/succeeded? result)
    (move-spec! provenance :done)
    (move-spec! provenance :failed)))

;------------------------------------------------------------------------------ Layer 0.5
;; Workflow interface resolution and pipeline helpers

(defn resolve-workflow-interface []
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

(defn create-phase-callbacks [_quiet]
  ;; Phase progress is handled by the event-stream subscription
  ;; (display/start-progress!). Callbacks retained as extension point.
  {})

(defn load-and-validate-workflow [load-workflow workflow-id version]
  (let [{:keys [workflow validation]} (load-workflow workflow-id version {})]
    (when-not workflow
      (throw (ex-info (messages/t :workflow-runner/not-found {:workflow-id workflow-id})
                      {:workflow-id workflow-id :version version})))
    (when (and validation (not (:valid? validation)))
      (throw (ex-info (messages/t :workflow-runner/validation-failed {:errors (:errors validation)})
                      {:workflow-id workflow-id :validation validation})))
    workflow))

(defn create-artifact-store [quiet]
  (try
    (when-let [create-transit-store (requiring-resolve 'ai.miniforge.artifact.interface/create-transit-store)]
      (create-transit-store))
    (catch Exception _e
      (when-not quiet
        (println (display/colorize :yellow (messages/t :workflow-runner/artifact-store-warning))))
      nil)))

(defn close-artifact-store [artifact-store]
  (when artifact-store
    (try
      (when-let [close-store (requiring-resolve 'ai.miniforge.artifact.interface/close-store)]
        (close-store artifact-store))
      (catch Exception _))))

(defn select-workflow-type
  "Select workflow type using LLM recommendation if not explicitly specified."
  [spec llm-client quiet]
  (if-let [explicit-type (:spec/workflow-type spec)]
    (do
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/user-specified {:workflow-type (name explicit-type)}))))
      explicit-type)
    (let [recommendation (recommender/recommend-workflow-with-fallback spec llm-client)]
      (when-not quiet
        (println (display/colorize :cyan (messages/t :workflow-runner/auto-selected {:workflow-type (name (:workflow recommendation))})))
        (println (messages/t :workflow-runner/auto-selected-reason {:reasoning (:reasoning recommendation)}))
        (when (= :llm (:source recommendation))
          (println (messages/t :workflow-runner/auto-selected-confidence {:confidence (format "%.0f%%" (* 100 (:confidence recommendation 0.0)))})))
        (println (display/colorize :yellow (messages/t :workflow-runner/auto-selected-override))))
      (:workflow recommendation))))

(defn load-or-create-workflow [load-workflow workflow-type workflow-version]
  (try
    (load-and-validate-workflow load-workflow workflow-type workflow-version)
    (catch Exception e
      (case workflow-type
        :test-only
        {:workflow/id :test-only
         :workflow/version "inline"
         :workflow/name "Test Generation"
         :workflow/pipeline [{:phase :verify} {:phase :done}]
         :workflow/config {:max-tokens 20000 :max-iterations 10}}

        :comment-fix
        {:workflow/id :comment-fix
         :workflow/version "inline"
         :workflow/name "Comment Fix"
         :workflow/pipeline [{:phase :implement :gates [:syntax :lint :no-secrets]}
                             {:phase :done}]
         :workflow/config {:max-tokens 20000 :max-iterations 5}}

        (throw e)))))

(defn execute-workflow-pipeline [run-pipeline workflow input callbacks artifact-store event-stream]
  (-> callbacks
      (cond-> artifact-store (assoc :artifact-store artifact-store))
      (cond-> event-stream (assoc :event-stream event-stream))
      (->> (run-pipeline workflow input))))

(defn publish-completion-event [event-stream workflow-id result]
  (let [status (if (phase/succeeded? result) :success :failure)
        duration-ms (get-in result [:execution/metrics :duration-ms])]
    (es/publish! event-stream
                 (if (= status :success)
                   (es/workflow-completed event-stream workflow-id status duration-ms)
                   (es/workflow-failed event-stream workflow-id
                                       {:message (str (first (:execution/errors result)))
                                        :errors (:execution/errors result)})))))

;------------------------------------------------------------------------------ Layer 1
;; Execution orchestration

(defn execute-with-events [{:keys [run-pipeline workflow workflow-input context artifact-store
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
                                             {:message (messages/t :workflow-runner/stopped {:error (ex-message e)})
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
                                             {:message (messages/t :workflow-runner/cancelled)
                                              :errors [{:type :cancelled :message (messages/t :workflow-runner/process-terminated)}]}))
            (catch Exception _ nil)))
        (when sandbox-cleanup
          (sandbox-cleanup)
          (when-not (:quiet opts)
            (println (display/colorize :yellow (messages/t :workflow-runner/sandbox-released)))))))))

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
            progress-cleanup (display/start-progress! es quiet)]
        (try
          (let [result (execute-workflow-pipeline run-pipeline workflow workflow-input callbacks-with-url artifact-store es)]
            (close-artifact-store artifact-store)
            (display/print-result result opts)
            result)
          (finally
            (progress-cleanup)))))
    (catch Exception e
      (when-not quiet
        (println (display/colorize :red (messages/t :workflow-runner/run-error {:error (ex-message e)}))))
      (when (= output :json)
        (println (json/generate-string
                  {:status "error"
                   :error (ex-message e)
                   :data (ex-data e)}
                  {:pretty true})))
      (throw e))))

(defn format-workflow-listing [workflows]
  (if (empty? workflows)
    (println (messages/t :workflow-runner/no-workflows))
    (do
      (println (display/colorize :cyan (messages/t :workflow-runner/available-workflows)))
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (doseq [{:workflow/keys [id version description type]} workflows]
        (println (str (display/colorize :bold (str "  " (name id)))
                      " (v" version ")"
                      "  " (display/colorize :yellow (messages/t :workflow-runner/workflow-type-label {:type (or type :unknown)}))
                      (when description (str "\n    " description))))
        (println))
      (println (display/colorize :cyan (apply str (repeat 60 "─")))))))

(defn list-workflows-from-resources []
  (try
    (let [list-workflows (requiring-resolve 'ai.miniforge.workflow.interface/list-workflows)]
      (->> (list-workflows)
           (sort-by (juxt :workflow/id :workflow/version))
           format-workflow-listing))
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-failed {:error (ex-message e)}))))))

(defn list-workflows! []
  (try
    (list-workflows-from-resources)
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-failed {:error (ex-message e)})))
      (throw e))))

;------------------------------------------------------------------------------ Layer 2
;; Spec-driven execution

(defn run-workflow-from-spec! [spec {:keys [quiet] :or {quiet false} :as opts}]
  (try
    (let [{:keys [load-workflow run-pipeline]} (resolve-workflow-interface)
          ;; Create initial LLM client for workflow selection
          backend-override (:backend opts)
          selection-llm-client (context/create-llm-client nil spec quiet backend-override)
          workflow-type (select-workflow-type spec selection-llm-client quiet)
          workflow-version (get spec :spec/workflow-version "latest")
          workflow (load-or-create-workflow load-workflow workflow-type workflow-version)
          enriched-spec (context/decorate-spec-with-runtime-context spec opts)
          ;; Infer repo URL and branch for execution environment (Docker clone / worktree).
          ;; Reuses sandbox helpers which fall back to `git remote get-url origin`.
          repo-url (sandbox/infer-repo-url spec enriched-spec)
          ;; In governed mode, always clone main — the capsule creates a fresh
          ;; working copy, not a checkout of the host worktree's branch.
          branch   (if (= :governed (:execution-mode opts))
                     (or (:spec/branch spec) "main")
                     (sandbox/infer-branch spec enriched-spec))
          workflow-input (context/spec->workflow-input enriched-spec)
          artifact-store (create-artifact-store quiet)
          event-stream (es/create-event-stream)
          workflow-id (or (get-in enriched-spec [:spec/metadata :session-id]) (random-uuid))
          ;; Control state for dashboard commands (pause/resume/stop)
          control-state (es/create-control-state)
          command-poller-cleanup (dashboard/start-command-poller! workflow-id control-state)
          ;; Create workflow-specific LLM client for execution
          llm-client (context/create-llm-client workflow spec quiet backend-override)
          callbacks (create-phase-callbacks quiet)
          base-context (let [ctx (context/create-workflow-context
                             {:callbacks callbacks
                              :artifact-store artifact-store
                              :event-stream event-stream
                              :workflow-id workflow-id
                              :workflow-type workflow-type
                              :workflow-version workflow-version
                              :llm-client llm-client
                              :quiet quiet
                              :spec-title (:spec/title spec)
                              :control-state control-state
                              :skip-lifecycle-events true
                              :execution-opts (:execution-opts opts)})]
                        ;; Assoc repo-url, branch, and (optionally) execution-mode
                        ;; so runner.clj can clone into Docker or create a worktree.
                        (assoc ctx
                               :repo-url repo-url
                               :branch branch
                               :execution-mode (get opts :execution-mode :local)))
          sandbox? (or (:sandbox opts) (:spec/sandbox spec))
          [context sandbox-cleanup] (sandbox/setup-sandbox-context base-context sandbox? spec enriched-spec quiet)
          progress-cleanup (display/start-progress! event-stream quiet)]
      (when-not quiet
        (display/print-workflow-header (keyword (str "adhoc-" (hash spec))) "adhoc" quiet))
      (dashboard/print-dashboard-status! quiet)
      (let [provenance (:spec/provenance enriched-spec)]
        (move-spec-to-in-progress! provenance)
        (try
          (let [result (execute-with-events {:run-pipeline run-pipeline
                                             :workflow workflow
                                             :workflow-input workflow-input
                                             :context context
                                             :artifact-store artifact-store
                                             :event-stream event-stream
                                             :workflow-id workflow-id
                                             :sandbox-cleanup sandbox-cleanup
                                             :opts opts})]
            (move-spec-on-completion! provenance result)
            result)
          (finally
            (progress-cleanup)
            (when command-poller-cleanup (command-poller-cleanup))))))
    (catch Exception e
      (when-not quiet
        (println (display/colorize :red (messages/t :workflow-runner/spec-execution-failed {:error (ex-message e)}))))
      (throw e))))

;------------------------------------------------------------------------------ Layer 2b
;; Resume workflow from event file

(defn resume-workflow-from-spec!
  "Resume a previously failed/paused workflow by replaying its event file
   to determine which DAG tasks already completed, then re-running the spec
   with those tasks pre-completed.

   Arguments:
   - workflow-id: UUID string of the workflow to resume
   - spec: The original spec map (same spec file used for the initial run)
   - opts: Same opts as run-workflow-from-spec!"
  [workflow-id-str spec {:keys [quiet] :or {quiet false} :as opts}]
  (let [resume-ctx (try
                     (let [resume-fn (requiring-resolve
                                      'ai.miniforge.workflow.dag-resilience/resume-context-from-event-file)]
                       (resume-fn workflow-id-str))
                     (catch Exception e
                       (when-not quiet
                         (println (display/colorize :red
                                   (messages/t :workflow-runner/event-file-failed {:error (ex-message e)}))))
                       nil))]
    (when-not quiet
      (println (display/colorize :cyan
                (messages/t :workflow-runner/resuming {:workflow-id workflow-id-str})))
      (println (display/colorize :cyan
                (messages/t :workflow-runner/previously-completed {:count (count (:pre-completed-ids resume-ctx))})))
      (when (seq (:pre-completed-artifacts resume-ctx))
        (println (display/colorize :cyan
                  (messages/t :workflow-runner/recovered-artifacts {:count (count (:pre-completed-artifacts resume-ctx))})))))
    (if (and resume-ctx (seq (:pre-completed-ids resume-ctx)))
      ;; Re-run with pre-completed task IDs injected
      (let [opts-with-resume (assoc-in opts [:execution-opts :pre-completed-dag-tasks]
                                      (:pre-completed-ids resume-ctx))]
        (run-workflow-from-spec! spec opts-with-resume))
      (do
        (when-not quiet
          (println (display/colorize :yellow
                    (messages/t :workflow-runner/no-completed-tasks))))
        (run-workflow-from-spec! spec opts)))))

;------------------------------------------------------------------------------ Layer 3
;; Chain-driven execution

(defn resolve-chain-input
  "Resolve chain input from a spec file path or inline JSON."
  [opts]
  (let [spec-path (:spec opts)
        inline-json (:input-json opts)]
    (cond
      inline-json (json/parse-string inline-json true)
      spec-path (let [parsed (edn/read-string (slurp spec-path))
                      enriched (context/decorate-spec-with-runtime-context parsed {})]
                  (context/spec->workflow-input enriched))
      :else {})))

(defn print-chain-header
  "Print chain execution banner."
  [chain-id chain-def quiet]
  (when-not quiet
    (println)
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-header {:chain-id (name chain-id)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-description {:description (:chain/description chain-def)})))
    (println (display/colorize :cyan (messages/t :workflow-runner/chain-steps {:count (count (:chain/steps chain-def))})))
    (println (display/colorize :cyan (apply str (repeat 60 "─"))))))

(defn print-chain-result
  "Print chain execution result summary."
  [result quiet]
  (when-not quiet
    (let [steps (:chain/step-results result)
          duration (:chain/duration-ms result)]
      (println)
      (println (display/colorize :cyan (apply str (repeat 60 "─"))))
      (if (phase/succeeded? result)
        (println (display/colorize :green (messages/t :workflow-runner/chain-completed {:count (count steps) :duration duration})))
        (let [failed-step (some #(when (phase/failed? %) (:step/id %)) steps)]
          (println (display/colorize :red (messages/t :workflow-runner/chain-failed-at {:step (when failed-step (name failed-step))}))))))))

(defn run-chain!
  "Execute a chain of workflows.

   Arguments:
   - chain-id: Chain identifier keyword (e.g. :reporting-chain)
   - opts: {:version \"latest\" :spec \"spec.edn\" :input-json \"{...}\" :quiet false}"
  [chain-id opts]
  (let [quiet (get opts :quiet false)
        version (get opts :version "latest")]
    (try
      (let [load-chain-fn (requiring-resolve 'ai.miniforge.workflow.interface/load-chain)
            run-chain-fn (requiring-resolve 'ai.miniforge.workflow.interface/run-chain)
            chain-result (load-chain-fn chain-id version)
            chain-def (:chain chain-result)
            chain-input (resolve-chain-input opts)
            event-stream (es/create-event-stream)
            llm-client (context/create-llm-client nil nil quiet)
            callbacks (create-phase-callbacks quiet)
            context (context/create-workflow-context {:callbacks callbacks
                                                      :event-stream event-stream
                                                      :llm-client llm-client
                                                      :quiet quiet
                                                      :workflow-id (random-uuid)
                                                      :workflow-type chain-id
                                                      :workflow-version version
                                                      :spec-title (str "Chain: " (name chain-id))
                                                      :control-state (es/create-control-state)})
            progress-cleanup (display/start-progress! event-stream quiet)]
        (print-chain-header chain-id chain-def quiet)
        (dashboard/print-dashboard-status! quiet)
        (try
          (let [result (run-chain-fn chain-def chain-input context)]
            (print-chain-result result quiet)
            result)
          (finally
            (progress-cleanup))))
      (catch Exception e
        (when-not quiet
          (println (display/colorize :red (messages/t :workflow-runner/chain-execution-failed {:error (ex-message e)}))))
        (throw e)))))

(defn list-chains!
  "List all available chain definitions."
  []
  (try
    (let [list-chains-fn (requiring-resolve 'ai.miniforge.workflow.interface/list-chains)
          chains (list-chains-fn)]
      (if (empty? chains)
        (println (messages/t :workflow-runner/no-chains))
        (do
          (println (display/colorize :cyan (messages/t :workflow-runner/available-chains)))
          (println (display/colorize :cyan (apply str (repeat 60 "─"))))
          (doseq [{:keys [id version description steps]} chains]
            (println (str (display/colorize :bold (str "  " (name id)))
                          " (v" version ")"
                          "  " (messages/t :workflow-runner/chain-steps-label {:steps steps})))
            (when description
              (println (str "    " description)))
            (println))
          (println (display/colorize :cyan (apply str (repeat 60 "─")))))))
    (catch Exception e
      (println (display/colorize :red (messages/t :workflow-runner/list-chains-failed {:error (ex-message e)})))
      (throw e))))
