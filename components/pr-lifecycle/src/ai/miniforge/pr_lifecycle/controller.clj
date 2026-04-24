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

(ns ai.miniforge.pr-lifecycle.controller
  "PR lifecycle controller - orchestrates task→PR→merge workflow.

   This controller uses an explicit FSM-backed status model to drive a task
   through PR creation, CI/review monitoring, fix loops, and merge."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.ci-monitor :as ci]
   [ai.miniforge.pr-lifecycle.controller-config :as controller-config]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix]
   [ai.miniforge.pr-lifecycle.fsm :as controller-fsm]
   [ai.miniforge.pr-lifecycle.messages :as messages]
   [ai.miniforge.pr-lifecycle.merge :as merge]
   [ai.miniforge.pr-lifecycle.review-monitor :as review]
   [ai.miniforge.release-executor.interface :as release]
   [ai.miniforge.schema.interface :as schema]
   [ai.miniforge.logging.interface :as log]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Controller state

(def ^:private lifecycle-loop-sleep-ms
  1000)

(def ^:private lifecycle-failed-status
  :failed)

(defn- format-acceptance-criteria
  [criteria]
  (->> criteria
       (map #(messages/t :controller/criterion-line {:criterion %}))
       (str/join "\n")))

(defn- task-id-fragment
  [task-id]
  (let [task-id-string (str task-id)
        fragment-length (min 8 (count task-id-string))]
    (subs task-id-string 0 fragment-length)))

(defn- build-commit-message
  [task task-id]
  (let [title (get task :task/title (messages/t :controller/default-commit-title))]
    (messages/t :controller/commit-message
                {:title title
                 :task-id task-id})))

(defn- build-pr-title
  [task task-id]
  (get task :task/title
       (messages/t :controller/default-pr-title
                   {:task-fragment (task-id-fragment task-id)})))

(defn- build-pr-body
  [task]
  (let [description (get task :task/description
                         (messages/t :controller/default-task-description))
        criteria (format-acceptance-criteria
                  (get task :task/acceptance-criteria []))]
    (messages/t :controller/pr-body-template
                {:description description
                 :criteria criteria})))

(defn- controller-config-map
  [worktree-path merge-policy controller-defaults]
  {:worktree-path worktree-path
   :merge-policy merge-policy
   :max-fix-iterations (:max-fix-iterations controller-defaults)
   :ci-poll-interval-ms (:ci-poll-interval-ms controller-defaults)
   :review-poll-interval-ms (:review-poll-interval-ms controller-defaults)
   :auto-resolve-comments (:auto-resolve-comments controller-defaults)
   :branch-name-prefix (:branch-name-prefix controller-defaults)})

(defn- invalid-transition-ex
  "Create an exception describing an invalid controller status transition."
  [current-status new-status transition-result]
  (ex-info (messages/t :controller/invalid-transition)
           {:from current-status
            :to new-status
            :error (controller-fsm/transition-error-code transition-result)
            :message (controller-fsm/transition-error-message transition-result)
            :valid-targets (controller-fsm/valid-targets current-status)}))

(defn- result-status
  "Return a normalized status keyword for result payloads."
  [result]
  (if (schema/succeeded? result) :succeeded :failed))

(defn- fix-completion-data
  "Create history payload for a completed fix attempt."
  [fix-result]
  {:result-status (result-status fix-result)})

(defn- transition-status
  "Validate and apply a status transition to the current controller snapshot."
  [controller-state new-status]
  (let [current-status (:status controller-state)
        transition-result (controller-fsm/transition current-status new-status)]
    (if (controller-fsm/succeeded? transition-result)
      (assoc controller-state
             :status (controller-fsm/transition-state transition-result)
             :updated-at (java.util.Date.))
      (throw (invalid-transition-ex current-status new-status transition-result)))))

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
   & {:as opts}]
  (let [defaults (merge (controller-config/controller-defaults)
                        {:merge-policy merge/default-merge-policy}
                        opts)
        worktree-path (:worktree-path defaults)
        merge-policy (:merge-policy defaults)
        created-at (java.util.Date.)
        config (controller-config-map worktree-path merge-policy defaults)]
    (atom {:controller/id (random-uuid)
           :dag/id dag-id
           :run/id run-id
           :task/id task-id
           :task task

           ;; Configuration
           :config config

           ;; Dependencies
           :event-bus (:event-bus opts)
           :logger (:logger opts)
           :generate-fn (:generate-fn opts)

           ;; State
           :status controller-fsm/initial-status
           :pr nil
           :ci-monitor nil
           :review-monitor nil
           :fix-iterations 0
           :ci-retries 0
           :history []
           :created-at created-at
           :updated-at created-at})))

(defn update-status!
  "Update controller status using the formal PR lifecycle FSM."
  [controller new-status]
  (loop []
    (let [current-state @controller
          updated-state (transition-status current-state new-status)]
      (if (compare-and-set! controller current-state updated-state)
        (:status updated-state)
        (recur)))))

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
  (update-status! controller lifecycle-failed-status)
  (dag/err error-key error-msg))

(defn create-and-checkout-branch!
  "Create and checkout a new branch for the task.
   Returns the branch result or a DAG error."
  [controller worktree-path branch-name]
  (let [branch-result (release/create-branch! worktree-path branch-name)]
    (if (schema/succeeded? branch-result)
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
  (let [commit-msg (build-commit-message task task-id)
        commit-result (release/commit-changes! worktree-path commit-msg)]
    (if (schema/succeeded? commit-result)
      commit-result
      (fail-controller! controller :commit-failed (:error commit-result)))))

(defn push-and-create-pr!
  "Push the branch and open a GitHub PR.
   Returns the PR info map or a DAG error."
  [controller worktree-path branch-name base-branch task task-id commit-result]
  (let [push-result (release/push-branch! worktree-path branch-name)]
    (if-not (schema/succeeded? push-result)
      (fail-controller! controller :push-failed (:error push-result))
      (let [pr-title (build-pr-title task task-id)
            pr-body (build-pr-body task)
            pr-result (release/create-pr! worktree-path
                                          {:title pr-title
                                           :body pr-body
                                           :base-branch base-branch})]
        (if-not (schema/succeeded? pr-result)
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
              {:message (messages/t :controller/pr-created)
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
        {:keys [worktree-path branch-name-prefix]} config
        branch-name (str branch-name-prefix (task-id-fragment task-id))]

    (update-status! controller :creating-pr)
    (add-history! controller :pr-creation-started {:branch branch-name})

    (when logger
      (log/info logger :pr-lifecycle :controller/creating-pr
                {:message (messages/t :controller/creating-pr)
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
                  {:message (messages/t :controller/max-fix-iterations-exceeded)
                   :data {:task-id task-id :iterations current-iterations}}))
      (throw (ex-info (messages/t :controller/max-fix-iterations-exceeded)
                      {:task-id task-id :iterations current-iterations})))

    (update-status! controller :fixing)
    (swap! controller update :fix-iterations inc)
    (add-history! controller :ci-fix-started {:iteration (inc current-iterations)})

    (when logger
      (log/info logger :pr-lifecycle :controller/fixing-ci
                {:message (messages/t :controller/fixing-ci)
                 :data {:task-id task-id :iteration (inc current-iterations)}}))

    (let [fix-result (fix/fix-ci-failure
                      task pr ci-logs generate-fn
                      {:worktree-path worktree-path
                       :logger logger
                       :event-bus event-bus
                       :dag/id (:dag/id @controller)
                       :run/id (:run/id @controller)})]
      (add-history! controller :ci-fix-completed (fix-completion-data fix-result))
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
      (throw (ex-info (messages/t :controller/max-fix-iterations-exceeded)
                      {:task-id task-id :iterations current-iterations})))

    (update-status! controller :fixing)
    (swap! controller update :fix-iterations inc)
    (add-history! controller :review-fix-started {:iteration (inc current-iterations)})

    (when logger
      (log/info logger :pr-lifecycle :controller/fixing-review
                {:message (messages/t :controller/fixing-review)
                 :data {:task-id task-id :comment-count (count review-comments)}}))

    (let [fix-result (fix/fix-review-feedback
                      task pr review-comments generate-fn
                      {:worktree-path worktree-path
                       :logger logger
                       :event-bus event-bus
                       :dag/id (:dag/id @controller)
                       :run/id (:run/id @controller)
                       :auto-resolve-comments auto-resolve-comments})]
      (add-history! controller :review-fix-completed (fix-completion-data fix-result))
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
                {:message (messages/t :controller/attempting-merge)
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
                      {:message (messages/t :controller/merged)
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
                {:message (messages/t :controller/lifecycle-starting)
                 :data {:task-id (:task/id @controller)}}))

    (try
      ;; Step 1: Create PR if needed
      (when-not skip-pr-creation?
        (let [pr-result (create-pr! controller code-artifact)]
          (when (dag/err? pr-result)
            (throw (ex-info (messages/t :controller/pr-creation-failed)
                            {:error (:error pr-result)})))))

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
                    (if (schema/succeeded? fix-result)
                      (do
                        ;; Fix pushed - restart CI monitoring
                        (start-ci-monitoring! controller)
                        (recur))
                      (do
                        ;; Fix failed
                        (update-status! controller lifecycle-failed-status)
                        {:status lifecycle-failed-status :reason :fix-failed})))

                  ;; Unknown status
                  (do
                    (update-status! controller lifecycle-failed-status)
                    {:status lifecycle-failed-status :reason :unknown-ci-status}))))

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
                  (if (schema/succeeded? fix-result)
                    (do
                      ;; Fix pushed - restart CI monitoring
                      (start-ci-monitoring! controller)
                      (recur))
                    (do
                      ;; Fix failed
                      (update-status! controller lifecycle-failed-status)
                      {:status lifecycle-failed-status :reason :fix-failed})))

                ;; Unknown/timeout
                {:status :pending :waiting-for :review}))

            ;; Fixing in progress
            :fixing
            (do
              (Thread/sleep lifecycle-loop-sleep-ms)
              (recur))

            ;; Terminal states
            :merged
            {:status :merged}

            :failed
            {:status lifecycle-failed-status :history (:history @controller)}

            ;; Default
            (do
              (Thread/sleep lifecycle-loop-sleep-ms)
              (recur)))))

      (catch Exception e
        (let [final-status (if (= lifecycle-failed-status (:status @controller))
                             lifecycle-failed-status
                             (try
                               (update-status! controller lifecycle-failed-status)
                               lifecycle-failed-status
                               (catch clojure.lang.ExceptionInfo _
                                 (:status @controller))))]
        (add-history! controller :exception {:message (.getMessage e)})
        (when logger
          (log/error logger :pr-lifecycle :controller/exception
                     {:message (messages/t :controller/exception)
                      :data {:error (.getMessage e)}}))
        {:status final-status :error (.getMessage e)})))))

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
