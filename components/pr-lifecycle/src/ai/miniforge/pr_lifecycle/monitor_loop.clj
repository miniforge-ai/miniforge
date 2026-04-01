(ns ai.miniforge.pr-lifecycle.monitor-loop
  "PR monitor loop: poll → classify → route → act.

   The main orchestrator for autonomous PR comment resolution.
   Watches open miniforge-authored PRs, classifies incoming comments,
   and routes them to appropriate handlers:

   - Change-requests → fix-loop (implement → test → push) → reply
   - Questions → LLM reply without code changes
   - Bot comments → log and skip
   - Approvals / noise → skip

   Budget enforcement is a hard stop at every routing decision.
   Events emitted at each step for TUI visibility."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.classifier :as classifier]
   [ai.miniforge.pr-lifecycle.events :as events]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix-loop]
   [ai.miniforge.pr-lifecycle.github :as github]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-events :as mevents]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]))

;------------------------------------------------------------------------------ Layer 0
;; Configuration

(def default-config
  "Default PR monitor loop configuration."
  {:poll-interval-ms 60000          ; 60 seconds
   :self-author nil                 ; Must be set — miniforge's GitHub login
   :max-fix-attempts-per-comment 3
   :max-total-fix-attempts-per-pr 10
   :abandon-after-hours 72})

;------------------------------------------------------------------------------ Layer 0
;; Monitor state

(defn create-monitor
  "Create a PR monitor loop state.

   Arguments:
   - config: Monitor configuration map

   Required config keys:
   - :worktree-path — path to git repo
   - :self-author — miniforge's GitHub login (for loop prevention)

   Optional config keys:
   - :poll-interval-ms — polling interval (default 60000)
   - :event-bus — PR lifecycle event bus for publishing events
   - :logger — structured logger instance
   - :generate-fn — LLM generation function for fix cycles and Q&A
   - :max-fix-attempts-per-comment — per-comment limit (default 3)
   - :max-total-fix-attempts-per-pr — per-PR limit (default 10)
   - :abandon-after-hours — time limit in hours (default 72)

   Returns monitor state atom."
  [config]
  (let [merged (merge default-config config)]
    (atom {:config merged
           :watermarks (poller/load-watermarks)
           :budgets {}   ; pr-number → budget state
           :running? false
           :started-at nil
           :cycles 0
           :last-cycle-at nil
           :evidence {:comments-received 0
                      :comments-addressed 0
                      :fixes-pushed []
                      :questions-answered []}})))

;------------------------------------------------------------------------------ Layer 1
;; Internal helpers

(defn- emit!
  "Publish an event to the event bus if present."
  [monitor event]
  (let [{:keys [event-bus logger]} (:config @monitor)]
    (when event-bus
      (events/publish! event-bus event logger))))

(defn- get-or-create-budget
  "Get existing budget for a PR, or create a new one.
   Checks persistent storage first, then creates fresh."
  [monitor pr-number]
  (or (get-in @monitor [:budgets pr-number])
      (let [persisted (budget/load-budget pr-number)]
        (if persisted
          (do (swap! monitor assoc-in [:budgets pr-number] persisted)
              persisted)
          (let [config (:config @monitor)
                b (budget/create-budget
                   pr-number
                   (select-keys config [:max-fix-attempts-per-comment
                                        :max-total-fix-attempts-per-pr
                                        :abandon-after-hours]))]
            (swap! monitor assoc-in [:budgets pr-number] b)
            b)))))

(defn- update-budget!
  "Update budget state for a PR in memory and on disk."
  [monitor pr-number budget-state]
  (swap! monitor assoc-in [:budgets pr-number] budget-state)
  (budget/save-budget! budget-state))

;------------------------------------------------------------------------------ Layer 1
;; Comment handlers

(defn handle-change-request
  "Handle a change-request comment.

   Routes to the fix loop: implement → test → push → reply.
   Checks budget before proceeding. Returns result map.

   Safety: always posts reply before or simultaneously with push.
   Never force-pushes or amends — always new commit."
  [monitor pr-info classified-comment]
  (let [{:keys [worktree-path generate-fn logger event-bus]} (:config @monitor)
        pr-number (:pr/number pr-info)
        comment (:comment classified-comment)
        comment-id (:comment/id comment)
        pr-budget (get-or-create-budget monitor pr-number)]

    ;; Budget check — hard stop
    (if-let [exhausted (budget/any-budget-exhausted? pr-budget comment-id)]
      (let [summary (budget/budget-summary pr-budget)]
        (emit! monitor (mevents/budget-exhausted pr-number
                         (assoc summary :exhausted-by exhausted)))
        (emit! monitor (mevents/escalated pr-number :budget-exhausted
                         {:comment-id comment-id
                          :budget-summary summary}))
        (when logger
          (log/warn logger :pr-monitor :handler/budget-exhausted
                    {:message (str "Budget exhausted for PR #" pr-number ": " (name exhausted))
                     :data summary}))
        {:success? false
         :reason :budget-exhausted
         :exhausted-by exhausted})

      ;; Budget OK — proceed with fix
      (if-not generate-fn
        (do
          (when logger
            (log/warn logger :pr-monitor :handler/no-generate-fn
                      {:message "Cannot fix change-request — no generate-fn configured"}))
          {:success? false :reason :no-generate-fn})

        (let [updated-budget (budget/record-fix-attempt pr-budget comment-id)
              _ (update-budget! monitor pr-number updated-budget)
              attempt (get-in updated-budget [:comment-attempts comment-id])
              _ (emit! monitor (mevents/fix-started pr-number comment-id attempt))

              ;; Build task and failure info for fix-loop
              task {:task/id (random-uuid)
                    :task/type :fix
                    :task/acceptance-criteria
                    [(str "Address review comment: " (:comment/body comment))]}

              failure-info {:type :review-changes
                            :summary (:comment/body comment)
                            :comments [{:body (:comment/body comment)
                                        :author (:comment/author comment)
                                        :path (:comment/path comment)
                                        :line (:comment/line comment)}]
                            :affected-files (when (:comment/path comment)
                                              [(:comment/path comment)])
                            :comment-id comment-id
                            :parent-pr-number pr-number}

              context {:logger logger
                       :worktree-path worktree-path}

              ;; Run fix loop — implement → test → push
              fix-result (fix-loop/run-fix-loop
                          task pr-info failure-info generate-fn context
                          :worktree-path worktree-path
                          :max-attempts 1   ; Single attempt per cycle
                          :event-bus event-bus
                          :auto-resolve-comments true)]

          (if (:success? fix-result)
            (let [commit-sha (:commit-sha fix-result)
                  final-budget (budget/record-fix-pushed updated-budget)]
              (update-budget! monitor pr-number final-budget)

              ;; Emit fix-pushed event
              (emit! monitor (mevents/fix-pushed pr-number comment-id commit-sha))

              ;; Post reply citing the fix (safety: reply accompanies push)
              (let [reply-body (str "Addressed in commit " commit-sha
                                    ".\n\n---\n*Fixed by miniforge's autonomous PR monitor.*")]
                (poller/post-comment worktree-path pr-number reply-body)
                (emit! monitor (mevents/reply-posted pr-number comment-id :fix-reply)))

              ;; Update evidence
              (swap! monitor update-in [:evidence :fixes-pushed] conj
                     {:comment-id comment-id :sha commit-sha :at (java.util.Date.)})
              (swap! monitor update-in [:evidence :comments-addressed] inc)

              (when logger
                (log/info logger :pr-monitor :handler/fix-pushed
                          {:message (str "Fix pushed for comment on PR #" pr-number)
                           :data {:commit-sha commit-sha :attempt attempt}}))
              {:success? true :commit-sha commit-sha :attempt attempt})

            (do
              (when logger
                (log/warn logger :pr-monitor :handler/fix-failed
                          {:message (str "Fix failed for comment on PR #" pr-number)
                           :data {:reason (:reason fix-result) :attempt attempt}}))
              {:success? false :reason (:reason fix-result) :attempt attempt})))))))

(defn handle-question
  "Handle a question comment.

   Generates an LLM reply and posts it. No code changes are made."
  [monitor pr-info classified-comment]
  (let [{:keys [worktree-path generate-fn logger]} (:config @monitor)
        pr-number (:pr/number pr-info)
        comment (:comment classified-comment)
        comment-id (:comment/id comment)
        body (:comment/body comment)]

    (if-not generate-fn
      (do
        (when logger
          (log/warn logger :pr-monitor :handler/no-generate-fn
                    {:message "Cannot answer question — no generate-fn configured"
                     :data {:pr-number pr-number}}))
        {:success? false :reason :no-generate-fn})

      (let [prompt (str "You are miniforge, an autonomous software development agent. "
                        "A reviewer left this question on a pull request you authored. "
                        "Answer clearly and concisely. Do not make code changes.\n\n"
                        "Question:\n" body "\n\n"
                        "Write only the answer text, no preamble.")
            answer (try
                     (generate-fn prompt)
                     (catch Exception e
                       (when logger
                         (log/error logger :pr-monitor :handler/llm-error
                                    {:message "LLM call failed for question"
                                     :data {:error (.getMessage e)}}))
                       nil))]
        (if answer
          (let [reply-body (str answer
                                "\n\n---\n*Answered by miniforge's autonomous PR monitor.*")
                post-result (poller/post-comment worktree-path pr-number reply-body)]
            (if (dag/ok? post-result)
              (do
                (emit! monitor (mevents/question-answered pr-number comment-id))
                (emit! monitor (mevents/reply-posted pr-number comment-id :question-reply))

                ;; Update budget and evidence
                (let [pr-budget (get-or-create-budget monitor pr-number)]
                  (update-budget! monitor pr-number
                                  (budget/record-question-answered pr-budget)))
                (swap! monitor update-in [:evidence :questions-answered] conj
                       {:comment-id comment-id :at (java.util.Date.)})
                (swap! monitor update-in [:evidence :comments-addressed] inc)

                (when logger
                  (log/info logger :pr-monitor :handler/question-answered
                            {:message (str "Answered question on PR #" pr-number)
                             :data {:comment-id comment-id}}))
                {:success? true :type :question-answered})

              (do
                (when logger
                  (log/warn logger :pr-monitor :handler/post-failed
                            {:message "Failed to post question answer"
                             :data {:pr-number pr-number}}))
                {:success? false :reason :post-failed})))

          {:success? false :reason :llm-failed})))))

(defn handle-bot-comment
  "Handle a bot comment. Log and skip — no autonomous action."
  [monitor _pr-info classified-comment]
  (let [logger (get-in @monitor [:config :logger])
        comment (:comment classified-comment)]
    (when logger
      (log/debug logger :pr-monitor :handler/bot-comment
                 {:message "Bot comment — skipping"
                  :data {:author (:comment/author comment)}}))
    {:success? true :type :bot-skipped}))

(defn handle-approval
  "Handle an approval comment. Log for evidence, no action needed."
  [monitor pr-info classified-comment]
  (let [logger (get-in @monitor [:config :logger])
        pr-number (:pr/number pr-info)
        comment (:comment classified-comment)]
    (when logger
      (log/info logger :pr-monitor :handler/approval
                {:message (str "Approval received on PR #" pr-number)
                 :data {:author (:comment/author comment)}}))
    {:success? true :type :approval-noted}))

;------------------------------------------------------------------------------ Layer 2
;; Comment routing

(defn route-comment
  "Route a classified comment to its handler.

   Arguments:
   - monitor: Monitor state atom
   - pr-info: PR info map
   - classified: Classification result from classifier

   Returns handler result map."
  [monitor pr-info classified]
  (case (:category classified)
    :change-request (handle-change-request monitor pr-info classified)
    :question       (handle-question monitor pr-info classified)
    :bot-comment    (handle-bot-comment monitor pr-info classified)
    :approval       (handle-approval monitor pr-info classified)
    :noise          {:success? true :type :noise-skipped}
    ;; Unknown — skip safely
    {:success? true :type :unknown-skipped :category (:category classified)}))

;------------------------------------------------------------------------------ Layer 2
;; Single cycle

(defn run-cycle
  "Run one poll → classify → route cycle for a single PR.

   Arguments:
   - monitor: Monitor state atom
   - pr-info: PR info map {:pr/number :pr/branch :pr/sha ...}

   Returns:
   {:processed int :results [...] :new-comments int
    :classified-stats map :stopped? bool :stop-reason keyword}"
  [monitor pr-info]
  (let [{:keys [worktree-path self-author generate-fn logger]} (:config @monitor)
        pr-number (:pr/number pr-info)
        watermarks (:watermarks @monitor)

        ;; Check time budget first
        pr-budget (get-or-create-budget monitor pr-number)]

    (if (budget/time-budget-exhausted? pr-budget)
      (do
        (emit! monitor (mevents/budget-exhausted pr-number
                         (assoc (budget/budget-summary pr-budget)
                                :exhausted-by :time-limit)))
        (emit! monitor (mevents/escalated pr-number :time-limit
                         {:budget-summary (budget/budget-summary pr-budget)}))
        {:processed 0 :results [] :new-comments 0
         :stopped? true :stop-reason :time-budget-exhausted})

      ;; Poll for new comments
      (let [poll-result (poller/poll-pr-for-new-comments
                         worktree-path pr-number watermarks logger)]

        (if (dag/err? poll-result)
          {:processed 0 :results [] :new-comments 0
           :error (:error poll-result)}

          (let [{:keys [new-comments watermarks]} (:data poll-result)

                ;; Persist watermarks
                _ (swap! monitor assoc :watermarks watermarks)
                _ (poller/save-watermarks! watermarks)

                ;; Update evidence counter
                _ (swap! monitor update-in [:evidence :comments-received]
                         + (count new-comments))

                ;; Emit poll event
                _ (emit! monitor (mevents/poll-completed pr-number
                                   {:new-comment-count (count new-comments)}))

                ;; Classify all new comments
                classified (when (seq new-comments)
                             (classifier/classify-comments new-comments
                               :generate-fn generate-fn
                               :self-author self-author))

                ;; Emit classification events
                _ (doseq [c (:all classified)]
                    (emit! monitor (mevents/comment-received pr-number (:comment c)))
                    (emit! monitor (mevents/comment-classified pr-number (:comment c)
                                     {:category (:category c)
                                      :confidence (:confidence c)
                                      :method (:method c)})))

                ;; Route actionable comments (change-requests + questions)
                actionable (concat (:change-requests classified)
                                   (:questions classified))
                results (mapv #(route-comment monitor pr-info %) actionable)]

            (emit! monitor (mevents/cycle-completed pr-number
                             {:new-comments (count new-comments)
                              :classified-stats (:stats classified)
                              :actions-taken (count results)}))

            {:processed (count results)
             :results results
             :new-comments (count new-comments)
             :classified-stats (:stats classified)}))))))

;------------------------------------------------------------------------------ Layer 2
;; Main loop

(defn run-monitor-loop
  "Run the PR monitor loop continuously.

   Polls open PRs authored by `author`, classifies comments, routes
   to handlers. Runs until stopped externally, budget exhausted,
   or no open PRs remain.

   Arguments:
   - monitor: Monitor state atom (from create-monitor)
   - author: GitHub login to filter PRs by (e.g. \"miniforge[bot]\")

   Returns evidence summary when loop completes."
  [monitor author]
  (let [{:keys [poll-interval-ms logger worktree-path]} (:config @monitor)]

    (swap! monitor assoc :running? true :started-at (java.util.Date.))

    (when logger
      (log/info logger :pr-monitor :loop/started
                {:message (str "PR monitor loop started for author: " author)
                 :data {:poll-interval-ms poll-interval-ms}}))

    (loop []
      (when (:running? @monitor)
        (let [pr-result (poller/poll-open-prs worktree-path author)]

          (if (dag/err? pr-result)
            (do
              (when logger
                (log/warn logger :pr-monitor :loop/poll-prs-failed
                          {:message "Failed to poll open PRs — retrying"
                           :data {:error (:error pr-result)}}))
              (Thread/sleep poll-interval-ms)
              (recur))

            (let [prs (:prs (:data pr-result))]

              (if (empty? prs)
                ;; No open PRs — loop complete
                (do
                  (when logger
                    (log/info logger :pr-monitor :loop/no-open-prs
                              {:message "No open PRs found — stopping monitor loop"}))
                  (swap! monitor assoc :running? false))

                ;; Process each PR
                (do
                  (doseq [pr prs]
                    (when (:running? @monitor)
                      (try
                        (let [cycle-result (run-cycle monitor pr)]
                          ;; If a PR's budget is exhausted, log but continue others
                          (when (:stopped? cycle-result)
                            (when logger
                              (log/info logger :pr-monitor :loop/pr-budget-stopped
                                        {:message (str "PR #" (:pr/number pr)
                                                       " stopped: " (:stop-reason cycle-result))}))))
                        (catch Exception e
                          (when logger
                            (log/error logger :pr-monitor :loop/cycle-error
                                       {:message "Error in monitor cycle"
                                        :data {:pr-number (:pr/number pr)
                                               :error (.getMessage e)}}))))))

                  ;; Advance cycle counter
                  (swap! monitor (fn [m]
                                   (-> m
                                       (update :cycles inc)
                                       (assoc :last-cycle-at (java.util.Date.)))))

                  ;; Sleep between cycles
                  (when (:running? @monitor)
                    (Thread/sleep poll-interval-ms)
                    (recur)))))))))

    ;; Build and return evidence
    (when logger
      (log/info logger :pr-monitor :loop/stopped
                {:message "PR monitor loop stopped"
                 :data {:cycles (:cycles @monitor)}}))

    (let [evidence (:evidence @monitor)]
      (merge evidence
             {:cycles (:cycles @monitor)
              :duration-hours (when-let [started (:started-at @monitor)]
                                (/ (double (- (System/currentTimeMillis)
                                              (.getTime ^java.util.Date started)))
                                   3600000.0))}))))

(defn stop-monitor-loop
  "Stop a running monitor loop gracefully."
  [monitor]
  (swap! monitor assoc :running? false))

(defn monitor-running?
  "Check if the monitor loop is currently running."
  [monitor]
  (:running? @monitor))

(defn monitor-evidence
  "Get current evidence from the monitor."
  [monitor]
  (:evidence @monitor))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Create monitor
  (def m (create-monitor
          {:worktree-path "/path/to/repo"
           :self-author "miniforge[bot]"
           :poll-interval-ms 30000
           :generate-fn (fn [prompt] "mock LLM response")
           :logger nil}))

  ;; Run one cycle for a specific PR
  (run-cycle m {:pr/number 123
                :pr/branch "feat/foo"
                :pr/sha "abc123"})

  ;; Route a pre-classified comment
  (route-comment m
                 {:pr/number 123 :pr/branch "feat/foo"}
                 {:category :question
                  :confidence :high
                  :comment {:comment/id 456
                            :comment/body "Why this approach?"
                            :comment/author "alice"}})

  ;; Start continuous loop in background
  ;; (future (run-monitor-loop m "miniforge[bot]"))

  ;; Stop gracefully
  ;; (stop-monitor-loop m)

  ;; Query evidence
  (monitor-evidence m)

  :leave-this-here)
