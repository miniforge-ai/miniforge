(ns ai.miniforge.pr-lifecycle.controller
  "PR lifecycle controller - orchestrates task→PR→merge workflow.

   This is the main state machine that drives a task through its
   PR lifecycle, coordinating CI/review monitoring, fix loops, and merge."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.ci-monitor :as ci]
   [ai.miniforge.pr-lifecycle.review-monitor :as review]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.release-executor.interface :as release]
   [ai.miniforge.logging.interface :as log]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Controller state

(defn create-controller
  "Create a PR lifecycle controller for a task.

   Arguments:
   - dag-id: DAG run ID
   - run-id: Run instance ID
   - task-id: Task ID
   - task: Task definition with acceptance criteria, constraints, etc.

   Options:
   - :worktree-path - Path to git worktree (required)
   - :event-bus - Event bus for publishing events
   - :logger - Logger instance
   - :generate-fn - Agent generation function for implementations/fixes
   - :merge-policy - Merge policy map
   - :max-fix-iterations - Max fix iterations (default 5)
   - :ci-poll-interval-ms - CI poll interval (default 30000)
   - :review-poll-interval-ms - Review poll interval (default 30000)
   - :auto-resolve-comments - Auto-resolve conversation threads after fixes (default true)

   Returns controller state atom."
  [dag-id run-id task-id task
   & {:keys [worktree-path event-bus logger generate-fn merge-policy
             max-fix-iterations ci-poll-interval-ms review-poll-interval-ms
             auto-resolve-comments]
      :or {max-fix-iterations 5
           ci-poll-interval-ms 30000
           review-poll-interval-ms 30000
           merge-policy merge/default-merge-policy
           auto-resolve-comments true}}]
  (atom {:controller/id (random-uuid)
         :dag/id dag-id
         :run/id run-id
         :task/id task-id
         :task task

         ;; Configuration
         :config {:worktree-path worktree-path
                  :merge-policy merge-policy
                  :max-fix-iterations max-fix-iterations
                  :ci-poll-interval-ms ci-poll-interval-ms
                  :review-poll-interval-ms review-poll-interval-ms
                  :auto-resolve-comments auto-resolve-comments}

         ;; Dependencies
         :event-bus event-bus
         :logger logger
         :generate-fn generate-fn

         ;; State
         :status :pending ; :pending :creating-pr :monitoring-ci :monitoring-review
                         ; :fixing :ready-to-merge :merging :merged :failed
         :pr nil         ; PR info once created
         :ci-monitor nil
         :review-monitor nil
         :fix-iterations 0
         :ci-retries 0
         :history []
         :created-at (java.util.Date.)
         :updated-at (java.util.Date.)}))

(defn update-status!
  "Update controller status with timestamp."
  [controller new-status]
  (swap! controller assoc
         :status new-status
         :updated-at (java.util.Date.))
  new-status)

(defn add-history!
  "Add an event to controller history."
  [controller event-type data]
  (swap! controller update :history conj
         {:type event-type
          :data data
          :timestamp (java.util.Date.)})
  nil)

;------------------------------------------------------------------------------ Layer 1
;; PR creation steps

(defn fail-controller!
  "Mark controller as failed and return a DAG error."
  [controller error-key error-msg]
  (update-status! controller :failed)
  (dag/err error-key error-msg))

(defn create-and-checkout-branch!
  "Create and checkout a new branch for the task.
   Returns the branch result or a DAG error."
  [controller worktree-path branch-name]
  (let [branch-result (release/create-branch! worktree-path branch-name)]
    (if (:success? branch-result)
      branch-result
      (do
        (add-history! controller :pr-creation-failed {:error (:error branch-result)})
        (fail-controller! controller :branch-creation-failed (:error branch-result))))))

(defn apply-code-to-files!
  "Apply the code artifact to the worktree.
   Returns the apply result or a DAG error."
  [controller worktree-path code-artifact logger]
  (let [apply-result (fix/apply-fix-to-worktree worktree-path code-artifact logger)]
    (if (dag/err? apply-result)
      (fail-controller! controller :artifact-apply-failed (:error apply-result))
      apply-result)))

(defn commit-changes!
  "Stage and commit all changes with the given task info.
   Returns the commit result or a DAG error."
  [controller worktree-path task task-id]
  (let [commit-msg (str "feat: " (or (:task/title task) "implement task")
                        "\n\nTask: " task-id)
        commit-result (release/commit-changes! worktree-path commit-msg)]
    (if (:success? commit-result)
      commit-result
      (fail-controller! controller :commit-failed (:error commit-result)))))

(defn push-and-create-pr!
  "Push the branch and open a GitHub PR.
   Returns the PR info map or a DAG error."
  [controller worktree-path branch-name base-branch task task-id commit-result]
  (let [push-result (release/push-branch! worktree-path branch-name)]
    (if-not (:success? push-result)
      (fail-controller! controller :push-failed (:error push-result))
      (let [pr-title (or (:task/title task) (str "Task " (subs (str task-id) 0 8)))
            pr-body  (str "## Task\n\n"
                          (or (:task/description task) "Automated task implementation")
                          "\n\n"
                          "## Acceptance Criteria\n\n"
                          (when-let [criteria (:task/acceptance-criteria task)]
                            (str/join "\n" (map #(str "- " %) criteria))))
            pr-result (release/create-pr! worktree-path
                                          {:title pr-title
                                           :body pr-body
                                           :base-branch base-branch})]
        (if-not (:success? pr-result)
          (fail-controller! controller :pr-creation-failed (:error pr-result))
          {:pr/id       (:pr-number pr-result)
           :pr/url      (:pr-url pr-result)
           :pr/branch   branch-name
           :pr/base-sha nil
           :pr/head-sha (:commit-sha commit-result)})))))

(defn finalize-pr-creation!
  "Record PR info on the controller, publish the event, and log success."
  [controller pr-info dag-id run-id task-id event-bus logger]
  (swap! controller assoc :pr pr-info)
  (add-history! controller :pr-created pr-info)
  (when event-bus
    (events/publish! event-bus
                     (events/pr-opened dag-id run-id task-id
                                       (:pr/id pr-info)
                                       (:pr/url pr-info)
                                       (:pr/branch pr-info)
                                       (:pr/head-sha pr-info))
                     logger))
  (when logger
    (log/info logger :pr-lifecycle :controller/pr-created
              {:message "PR created successfully"
               :data {:pr-url (:pr/url pr-info)}}))
  (dag/ok pr-info))

;------------------------------------------------------------------------------ Layer 1
;; PR creation orchestrator

(defn create-pr!
  "Create a PR for the task.

   Arguments:
   - controller: Controller state atom
   - code-artifact: Code artifact to commit

   Returns result with PR info."
  [controller code-artifact]
  (let [{dag-id :dag/id run-id :run/id task-id :task/id
         :keys [task config event-bus logger]} @controller
        {:keys [worktree-path]} config
        branch-name (str "task-" (subs (str task-id) 0 8))]

    (update-status! controller :creating-pr)
    (add-history! controller :pr-creation-started {:branch branch-name})

    (when logger
      (log/info logger :pr-lifecycle :controller/creating-pr
                {:message "Creating PR for task"
                 :data {:task-id task-id :branch branch-name}}))

    (let [branch-result (create-and-checkout-branch! controller worktree-path branch-name)]
      (if (dag/err? branch-result)
        branch-result
        (let [apply-result (apply-code-to-files! controller worktree-path code-artifact logger)]
          (if (dag/err? apply-result)
            apply-result
            (let [commit-result (commit-changes! controller worktree-path task task-id)]
              (if (dag/err? commit-result)
                commit-result
                (let [pr-info (push-and-create-pr! controller worktree-path branch-name
                                                   (:base-branch branch-result)
                                                   task task-id commit-result)]
                  (if (dag/err? pr-info)
                    pr-info
                    (finalize-pr-creation! controller pr-info
                                           dag-id run-id task-id
                                           event-bus logger)))))))))))

;------------------------------------------------------------------------------ Layer 1
;; Monitoring

(defn start-ci-monitoring!
  "Start CI monitoring for the PR."
  [controller]
  (let [{dag-id :dag/id run-id :run/id task-id :task/id
         :keys [pr config event-bus]} @controller
        {:keys [worktree-path ci-poll-interval-ms]} config]

    (update-status! controller :monitoring-ci)
    (add-history! controller :ci-monitoring-started {:pr-id (:pr/id pr)})

    (let [monitor (ci/create-ci-monitor
                   dag-id run-id task-id (:pr/id pr) worktree-path
                   :poll-interval-ms ci-poll-interval-ms
                   :event-bus event-bus)]
      (swap! controller assoc :ci-monitor monitor)
      monitor)))

(defn start-review-monitoring!
  "Start review monitoring for the PR."
  [controller]
  (let [{dag-id :dag/id run-id :run/id task-id :task/id
         :keys [pr config event-bus]} @controller
        {:keys [worktree-path review-poll-interval-ms merge-policy]} config]

    (update-status! controller :monitoring-review)
    (add-history! controller :review-monitoring-started {:pr-id (:pr/id pr)})

    (let [monitor (review/create-review-monitor
                   dag-id run-id task-id (:pr/id pr) worktree-path
                   :poll-interval-ms review-poll-interval-ms
                   :required-approvals (:required-approvals merge-policy 1)
                   :event-bus event-bus)]
      (swap! controller assoc :review-monitor monitor)
      monitor)))

;------------------------------------------------------------------------------ Layer 1
;; Fix handling

(defn handle-ci-failure!
  "Handle a CI failure by running the fix loop."
  [controller ci-logs]
  (let [{task-id :task/id :keys [task pr config event-bus logger generate-fn]} @controller
        {:keys [worktree-path max-fix-iterations]} config
        current-iterations (:fix-iterations @controller)]

    (when (>= current-iterations max-fix-iterations)
      (update-status! controller :failed)
      (add-history! controller :max-fix-iterations-exceeded {:iterations current-iterations})
      (when logger
        (log/warn logger :pr-lifecycle :controller/max-iterations
                  {:message "Max fix iterations exceeded"
                   :data {:task-id task-id :iterations current-iterations}}))
      (throw (ex-info "Max fix iterations exceeded"
                      {:task-id task-id :iterations current-iterations})))

    (update-status! controller :fixing)
    (swap! controller update :fix-iterations inc)
    (add-history! controller :ci-fix-started {:iteration (inc current-iterations)})

    (when logger
      (log/info logger :pr-lifecycle :controller/fixing-ci
                {:message "Running fix loop for CI failure"
                 :data {:task-id task-id :iteration (inc current-iterations)}}))

    (let [fix-result (fix/fix-ci-failure
                      task pr ci-logs generate-fn
                      {:worktree-path worktree-path
                       :logger logger
                       :event-bus event-bus
                       :dag/id (:dag/id @controller)
                       :run/id (:run/id @controller)})]
      (add-history! controller :ci-fix-completed {:success? (:success? fix-result)})
      fix-result)))

(defn handle-review-feedback!
  "Handle review feedback by running the fix loop."
  [controller review-comments]
  (let [{task-id :task/id :keys [task pr config event-bus logger generate-fn]} @controller
        {:keys [worktree-path max-fix-iterations auto-resolve-comments]} config
        current-iterations (:fix-iterations @controller)]

    (when (>= current-iterations max-fix-iterations)
      (update-status! controller :failed)
      (add-history! controller :max-fix-iterations-exceeded {:iterations current-iterations})
      (throw (ex-info "Max fix iterations exceeded"
                      {:task-id task-id :iterations current-iterations})))

    (update-status! controller :fixing)
    (swap! controller update :fix-iterations inc)
    (add-history! controller :review-fix-started {:iteration (inc current-iterations)})

    (when logger
      (log/info logger :pr-lifecycle :controller/fixing-review
                {:message "Running fix loop for review feedback"
                 :data {:task-id task-id :comment-count (count review-comments)}}))

    (let [fix-result (fix/fix-review-feedback
                      task pr review-comments generate-fn
                      {:worktree-path worktree-path
                       :logger logger
                       :event-bus event-bus
                       :dag/id (:dag/id @controller)
                       :run/id (:run/id @controller)
                       :auto-resolve-comments auto-resolve-comments})]
      (add-history! controller :review-fix-completed {:success? (:success? fix-result)})
      fix-result)))

;------------------------------------------------------------------------------ Layer 1
;; Merge

(defn attempt-merge!
  "Attempt to merge the PR."
  [controller]
  (let [{dag-id :dag/id run-id :run/id task-id :task/id
         :keys [pr config event-bus logger]} @controller
        {:keys [worktree-path merge-policy]} config]

    (update-status! controller :ready-to-merge)
    (add-history! controller :merge-attempted {:pr-id (:pr/id pr)})

    (when logger
      (log/info logger :pr-lifecycle :controller/merging
                {:message "Attempting to merge PR"
                 :data {:pr-id (:pr/id pr)}}))

    (let [merge-result (merge/attempt-merge
                        worktree-path (:pr/id pr) merge-policy
                        {:dag-id dag-id
                         :run-id run-id
                         :task-id task-id
                         :pr-id (:pr/id pr)
                         :event-bus event-bus
                         :logger logger})]
      (if (and (dag/ok? merge-result) (:merged? (:data merge-result)))
        (do
          (update-status! controller :merged)
          (add-history! controller :merged {:pr-id (:pr/id pr)})
          (when logger
            (log/info logger :pr-lifecycle :controller/merged
                      {:message "PR merged successfully"
                       :data {:pr-id (:pr/id pr)}})))
        (add-history! controller :merge-blocked {:reason (:error merge-result)}))
      merge-result)))

;------------------------------------------------------------------------------ Layer 2
;; Main control loop

(defn run-lifecycle!
  "Run the full PR lifecycle for a task.

   This is the main entry point that orchestrates:
   1. PR creation (if code artifact provided)
   2. CI monitoring
   3. Review monitoring
   4. Fix loops for failures
   5. Merge

   Arguments:
   - controller: Controller state atom
   - code-artifact: Initial code artifact (optional if PR already exists)

   Options:
   - :skip-pr-creation? - Skip PR creation, assume PR exists
   - :on-event - Callback (fn [event]) for lifecycle events

   Returns final status map."
  [controller code-artifact & {:keys [skip-pr-creation? on-event]}]
  (let [{:keys [logger]} @controller]

    (when logger
      (log/info logger :pr-lifecycle :controller/lifecycle-starting
                {:message "Starting PR lifecycle"
                 :data {:task-id (:task/id @controller)}}))

    (try
      ;; Step 1: Create PR if needed
      (when-not skip-pr-creation?
        (let [pr-result (create-pr! controller code-artifact)]
          (when (dag/err? pr-result)
            (throw (ex-info "PR creation failed" {:error (:error pr-result)})))))

      ;; Step 2: CI/Review loop
      (loop []
        (let [status (:status @controller)]
          (case status
            ;; Start CI monitoring
            (:creating-pr :monitoring-ci)
            (do
              (when (= status :creating-pr)
                (start-ci-monitoring! controller))
              (let [ci-monitor (:ci-monitor @controller)
                    ci-result (ci/run-ci-monitor
                               ci-monitor logger
                               :on-poll (fn [poll]
                                          (when on-event
                                            (on-event {:type :ci-poll :data poll}))))]
                (case (:status ci-result)
                  :success
                  (do
                    ;; CI passed - move to review monitoring
                    (start-review-monitoring! controller)
                    (recur))

                  (:failure :timed-out)
                  ;; CI failed - run fix loop
                  (let [fix-result (handle-ci-failure! controller (:ci/logs ci-result))]
                    (if (:success? fix-result)
                      (do
                        ;; Fix pushed - restart CI monitoring
                        (start-ci-monitoring! controller)
                        (recur))
                      (do
                        ;; Fix failed
                        (update-status! controller :failed)
                        {:status :failed :reason :fix-failed})))

                  ;; Unknown status
                  (do
                    (update-status! controller :failed)
                    {:status :failed :reason :unknown-ci-status}))))

            ;; Review monitoring
            :monitoring-review
            (let [review-monitor (:review-monitor @controller)
                  review-result (review/run-review-monitor
                                 review-monitor logger
                                 :on-poll (fn [poll]
                                            (when on-event
                                              (on-event {:type :review-poll :data poll})))
                                 :stop-on-status #{:approved :changes-requested})]
              (case (:status review-result)
                :approved
                ;; Ready to merge
                (let [merge-result (attempt-merge! controller)]
                  (if (and (dag/ok? merge-result) (:merged? (:data merge-result)))
                    {:status :merged}
                    (if (:rebased? (:data merge-result))
                      ;; Rebased - need to wait for CI again
                      (do
                        (start-ci-monitoring! controller)
                        (recur))
                      {:status :blocked :reason :merge-blocked})))

                :changes-requested
                ;; Run fix loop for review feedback
                (let [fix-result (handle-review-feedback!
                                  controller (:actionable-comments review-result))]
                  (if (:success? fix-result)
                    (do
                      ;; Fix pushed - restart CI monitoring
                      (start-ci-monitoring! controller)
                      (recur))
                    (do
                      ;; Fix failed
                      (update-status! controller :failed)
                      {:status :failed :reason :fix-failed})))

                ;; Unknown/timeout
                {:status :pending :waiting-for :review}))

            ;; Fixing in progress
            :fixing
            (do
              (Thread/sleep 1000) ; Wait for fix to complete
              (recur))

            ;; Terminal states
            :merged
            {:status :merged}

            :failed
            {:status :failed :history (:history @controller)}

            ;; Default
            (do
              (Thread/sleep 1000)
              (recur)))))

      (catch Exception e
        (update-status! controller :failed)
        (add-history! controller :exception {:message (.getMessage e)})
        (when logger
          (log/error logger :pr-lifecycle :controller/exception
                     {:message "Controller exception"
                      :data {:error (.getMessage e)}}))
        {:status :failed :error (.getMessage e)}))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create a controller
  (def controller
    (create-controller
     (random-uuid) (random-uuid) (random-uuid)
     {:task/id (random-uuid)
      :task/title "Implement feature X"
      :task/acceptance-criteria ["Tests pass" "No lint errors"]}
     :worktree-path "/path/to/repo"
     :generate-fn (fn [_task _ctx] {:artifact {:code/files []}
                                    :tokens 100})))

  ;; Check status
  (:status @controller)  ; => :pending

  ;; Run lifecycle (would need real worktree and generate-fn)
  ;; (run-lifecycle! controller {:code/files [{:path "foo.clj" :content "..."}]})

  :leave-this-here)
