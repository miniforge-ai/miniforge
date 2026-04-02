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

(ns ai.miniforge.pr-lifecycle.monitor-handlers
  "Comment handlers for the PR monitor loop."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.fix-loop :as fix-loop]
   [ai.miniforge.pr-lifecycle.monitor-budget :as budget]
   [ai.miniforge.pr-lifecycle.monitor-events :as mevents]
   [ai.miniforge.pr-lifecycle.monitor-state :as state]
   [ai.miniforge.pr-lifecycle.pr-poller :as poller]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers

(defn- succeeded?
  [result]
  (true? (:success? result)))

;------------------------------------------------------------------------------ Layer 1
;; Comment handlers

(defn handle-change-request
  "Handle a change-request comment by running one fix-loop attempt."
  [monitor pr-info classified-comment]
  (let [{:keys [worktree-path generate-fn logger event-bus]} (:config @monitor)
        pr-number (:pr/number pr-info)
        comment (:comment classified-comment)
        comment-id (:comment/id comment)
        pr-budget (state/get-or-create-budget monitor pr-number)]
    (if-let [exhausted (budget/any-budget-exhausted? pr-budget comment-id)]
      (let [summary (budget/budget-summary pr-budget)]
        (state/emit! monitor (mevents/budget-exhausted pr-number
                               (assoc summary :exhausted-by exhausted)))
        (state/emit! monitor (mevents/escalated pr-number :budget-exhausted
                               {:comment-id comment-id
                                :budget-summary summary}))
        (when logger
          (log/warn logger :pr-monitor :handler/budget-exhausted
                    {:message (str "Budget exhausted for PR #" pr-number ": "
                                   (name exhausted))
                     :data summary}))
        {:success? false
         :reason :budget-exhausted
         :exhausted-by exhausted})
      (if-not generate-fn
        (do
          (when logger
            (log/warn logger :pr-monitor :handler/no-generate-fn
                      {:message "Cannot fix change-request — no generate-fn configured"}))
          {:success? false :reason :no-generate-fn})
        (let [updated-budget (budget/record-fix-attempt pr-budget comment-id)
              _ (state/update-budget! monitor pr-number updated-budget)
              attempt (get-in updated-budget [:comment-attempts comment-id])
              _ (state/emit! monitor (mevents/fix-started pr-number comment-id attempt))
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
              fix-result (fix-loop/run-fix-loop
                          task pr-info failure-info generate-fn context
                          :worktree-path worktree-path
                          :max-attempts 1
                          :event-bus event-bus
                          :auto-resolve-comments true)]
          (if (succeeded? fix-result)
            (let [commit-sha (:commit-sha fix-result)
                  final-budget (budget/record-fix-pushed updated-budget)]
              (state/update-budget! monitor pr-number final-budget)
              (state/emit! monitor (mevents/fix-pushed pr-number comment-id commit-sha))
              (let [reply-body (str "Addressed in commit " commit-sha
                                    ".\n\n---\n*Fixed by miniforge's autonomous PR monitor.*")]
                (poller/post-comment worktree-path pr-number reply-body)
                (state/emit! monitor (mevents/reply-posted pr-number comment-id
                                                           :fix-reply)))
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
  "Handle a question comment by generating and posting a reply."
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
                (state/emit! monitor (mevents/question-answered pr-number comment-id))
                (state/emit! monitor (mevents/reply-posted pr-number comment-id
                                                           :question-reply))
                (let [pr-budget (state/get-or-create-budget monitor pr-number)]
                  (state/update-budget! monitor pr-number
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
  "Handle a bot comment. Log and skip."
  [monitor _pr-info classified-comment]
  (let [logger (get-in @monitor [:config :logger])
        comment (:comment classified-comment)]
    (when logger
      (log/debug logger :pr-monitor :handler/bot-comment
                 {:message "Bot comment — skipping"
                  :data {:author (:comment/author comment)}}))
    {:success? true :type :bot-skipped}))

(defn handle-approval
  "Handle an approval comment. Log and skip."
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
;; Routing

(defn route-comment
  "Route a classified comment to the appropriate handler."
  [monitor pr-info classified]
  (case (:category classified)
    :change-request (handle-change-request monitor pr-info classified)
    :question (handle-question monitor pr-info classified)
    :bot-comment (handle-bot-comment monitor pr-info classified)
    :approval (handle-approval monitor pr-info classified)
    :noise {:success? true :type :noise-skipped}
    {:success? true :type :unknown-skipped :category (:category classified)}))

