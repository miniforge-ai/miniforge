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
(ns ai.miniforge.pr-lifecycle.github-conversation
  "High-level reply and review-thread resolution workflows."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.github :as github]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reply-outcome
  "Build the shared successful-reply result shape."
  [reply-result resolved? details]
  (dag/ok (merge {:reply-posted true :resolved resolved?
                  :reply-url (:url (:data reply-result))}
                 details)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-after-reply
  "Resolve a replied-to thread when requested, preserving reply success."
  [worktree-path pr-number comment-id reply-result auto-resolve logger]
  (if-not auto-resolve
    (reply-outcome reply-result false {})
    (let [thread-result (github/get-thread-id worktree-path pr-number comment-id)]
      (cond
        (dag/err? thread-result)
        (do
          (when logger
            (log/warn logger :pr-lifecycle :github/thread-id-failed
                      {:message "Could not get thread ID for resolution"
                       :data {:error (:error thread-result) :comment-id comment-id}}))
          (reply-outcome reply-result false {:resolution-error (:error thread-result)}))

        (:is-resolved (:data thread-result))
        (let [thread-id (:thread-id (:data thread-result))]
          (when logger
            (log/info logger :pr-lifecycle :github/already-resolved
                      {:message "Thread already resolved"
                       :data {:thread-id thread-id}}))
          (reply-outcome reply-result true {:already-resolved true :thread-id thread-id}))

        :else
        (let [thread-id (:thread-id (:data thread-result))
              result (github/resolve-conversation worktree-path thread-id)]
          (if (dag/ok? result)
            (do
              (when logger
                (log/info logger :pr-lifecycle :github/conversation-resolved
                          {:message "Conversation resolved successfully"
                           :data {:thread-id thread-id :pr-number pr-number}}))
              (reply-outcome reply-result true {:thread-id thread-id}))
            (do
              (when logger
                (log/warn logger :pr-lifecycle :github/resolution-failed
                          {:message "Failed to resolve conversation"
                           :data {:error (:error result) :thread-id thread-id}}))
              (reply-outcome reply-result false {:resolution-error (:error result)
                                                 :thread-id thread-id}))))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} link-fix-pr-to-comment
  "Reply with a fix PR link and optionally resolve the review thread."
  [worktree-path pr-number comment-id fix-pr-number logger
   & {:keys [auto-resolve message-template]
      :or {auto-resolve true
           message-template "Fixed in PR #{fix-pr-number}"}}]
  (when logger
    (log/info logger :pr-lifecycle :github/linking-fix-pr
              {:message "Linking fix PR to comment"
               :data {:pr-number pr-number :comment-id comment-id :fix-pr-number fix-pr-number}}))
  (let [message (str/replace message-template "#{fix-pr-number}"
                             (str "#" fix-pr-number))
        reply-result (github/reply-to-comment worktree-path pr-number
                                              comment-id message)]
    (if (dag/err? reply-result)
      (do
        (when logger
          (log/warn logger :pr-lifecycle :github/reply-failed
                    {:message "Failed to post reply to comment"
                     :data {:error (:error reply-result) :pr-number pr-number
                            :comment-id comment-id}}))
        (dag/err :reply-failed
                 (get-in reply-result [:error :message]
                         "Failed to post reply to comment")
                 {:pr-number pr-number :comment-id comment-id
                  :fix-pr-number fix-pr-number
                  :cause (:error reply-result)}))
      (do
        (when logger
          (log/info logger :pr-lifecycle :github/reply-posted
                    {:message "Posted reply to comment"
                     :data {:reply-url (:url (:data reply-result))}}))
        (resolve-after-reply worktree-path pr-number comment-id
                             reply-result auto-resolve logger)))))
