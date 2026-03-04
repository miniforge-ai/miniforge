(ns ai.miniforge.cli.main.commands.resume
  "Resume a workflow from its last checkpoint using event files."
  (:require
   [babashka.fs :as fs]
   [clojure.edn :as edn]
   [clojure.string :as str]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.event-stream.interface :as es]))

;------------------------------------------------------------------------------ Layer 0
;; Event file parsing

(def ^:private events-dir
  (str (fs/home) "/.miniforge/events"))

(defn- read-event-file
  "Read all events from a workflow event file (one EDN map per line)."
  [workflow-id]
  (let [path (str events-dir "/" workflow-id ".edn")]
    (when-not (fs/exists? path)
      (throw (ex-info (str "Event file not found: " path)
                      {:workflow-id workflow-id :path path})))
    (->> (slurp path)
         str/split-lines
         (remove str/blank?)
         (mapv #(edn/read-string %)))))

;------------------------------------------------------------------------------ Layer 1
;; Context reconstruction

(defn- extract-completed-phases
  "Extract phase names that completed successfully from events."
  [events]
  (->> events
       (filter #(= :workflow/phase-completed (:event/type %)))
       (filter #(= :success (:phase/outcome %)))
       (mapv :workflow/phase)))

(defn- extract-phase-results
  "Build :execution/phase-results from phase-completed events."
  [events]
  (->> events
       (filter #(= :workflow/phase-completed (:event/type %)))
       (reduce (fn [acc evt]
                 (assoc acc (:workflow/phase evt)
                        {:outcome (:phase/outcome evt)
                         :duration-ms (:phase/duration-ms evt)
                         :timestamp (:event/timestamp evt)}))
               {})))

(defn- find-workflow-spec
  "Extract workflow spec from workflow-started event."
  [events]
  (->> events
       (filter #(= :workflow/started (:event/type %)))
       first
       :workflow/spec))

(defn reconstruct-context
  "Reconstruct execution context from event history."
  [workflow-id]
  (let [events (read-event-file workflow-id)
        by-type (group-by :event/type events)
        completed-phases (extract-completed-phases events)
        phase-results (extract-phase-results events)
        workflow-spec (find-workflow-spec events)
        started-event (first (get by-type :workflow/started))
        completed? (boolean (seq (get by-type :workflow/completed)))
        failed? (boolean (seq (get by-type :workflow/failed)))]
    {:phase-results phase-results
     :completed-phases completed-phases
     :workflow-spec workflow-spec
     :workflow-id (or (:workflow/id started-event) workflow-id)
     :completed? completed?
     :failed? failed?
     :event-count (count events)}))

;------------------------------------------------------------------------------ Layer 2
;; Public API

(defn resume-workflow
  "Resume a workflow from its last checkpoint.

   Reads event files to reconstruct state, determines resume point,
   and re-runs the pipeline with a trimmed pipeline (completed phases removed)."
  [workflow-id opts]
  (let [quiet (:quiet opts false)
        _ (when-not quiet
            (display/print-info (str "Resuming workflow: " workflow-id)))
        reconstructed (reconstruct-context (str workflow-id))]

    (if (:completed? reconstructed)
      (do (display/print-info "Workflow already completed successfully.") nil)

      (let [completed-phases (:completed-phases reconstructed)
            _ (when-not quiet
                (display/print-info (str "Completed phases: "
                                         (if (seq completed-phases)
                                           (str/join ", " (map name completed-phases))
                                           "none")))
                (display/print-info (str "Events found: " (:event-count reconstructed))))

            ;; Resolve workflow interface
            load-workflow (requiring-resolve 'ai.miniforge.workflow.interface/load-workflow)
            run-pipeline (requiring-resolve 'ai.miniforge.workflow.interface/run-pipeline)

            ;; Determine workflow type from event spec, fall back to lean-sdlc
            workflow-spec (:workflow-spec reconstructed)
            workflow-type (or (when workflow-spec (keyword (:name workflow-spec)))
                             :lean-sdlc)
            workflow-version (or (when workflow-spec (:version workflow-spec)) "latest")

            ;; Load the workflow definition
            {:keys [workflow]} (load-workflow workflow-type workflow-version {})
            pipeline (or (:workflow/pipeline workflow) [])

            ;; Trim pipeline: remove phases that already completed
            completed-set (set completed-phases)
            remaining-pipeline (vec (remove #(completed-set (:phase %)) pipeline))
            _ (when-not quiet
                (if (seq remaining-pipeline)
                  (display/print-info (str "Resuming from phase: "
                                           (name (:phase (first remaining-pipeline)))
                                           " (" (count remaining-pipeline) " phases remaining)"))
                  (display/print-info "All phases already completed.")))

            ;; Build a trimmed workflow with only remaining phases
            resume-workflow (assoc workflow :workflow/pipeline remaining-pipeline)

            ;; Set up execution infrastructure
            event-stream (es/create-event-stream)
            new-workflow-id (random-uuid)
            control-state (es/create-control-state)
            command-poller-cleanup (dashboard/start-command-poller! new-workflow-id control-state)
            llm-client (context/create-llm-client workflow nil quiet)]

        (when-not quiet
          (display/print-info (str "New workflow ID: " new-workflow-id)))

        (try
          (let [result (run-pipeline resume-workflow
                                     {}
                                     {:llm-backend llm-client
                                      :event-stream event-stream
                                      :control-state control-state
                                      :skip-lifecycle-events false
                                      :on-phase-start (fn [_ctx interceptor]
                                                        (when-not quiet
                                                          (display/print-info
                                                           (str "Phase: " (get-in interceptor [:config :phase])))))
                                      :on-phase-complete (fn [_ctx _interceptor _result] nil)})]
            (when-not quiet
              (display/print-info (str "Resumed workflow completed with status: "
                                       (:execution/status result))))
            result)
          (catch Exception e
            (display/print-error (str "Resume failed: " (ex-message e)))
            (throw e))
          (finally
            (when command-poller-cleanup (command-poller-cleanup))))))))
