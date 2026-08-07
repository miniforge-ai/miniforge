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
   [ai.miniforge.pr-lifecycle.github-conversation-resolution :as resolution]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reply-failure-result
  [pr-number comment-id fix-pr-number reply-result logger]
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

(defn- ^{:stratum 0} continue-after-reply
  [worktree-path pr-number comment-id reply-result auto-resolve logger]
  (when logger
    (log/info logger :pr-lifecycle :github/reply-posted
              {:message "Posted reply to comment"
               :data {:reply-url (:url (:data reply-result))}}))
  (resolution/resolve-after-reply worktree-path pr-number comment-id
                                  reply-result auto-resolve logger))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} link-fix-pr-to-comment
  "Reply with a fix PR link and optionally resolve the review thread."
  [worktree-path pr-number comment-id fix-pr-number logger
   & {:keys [auto-resolve message-template]
      :or {auto-resolve true
           message-template "Fixed in PR #{fix-pr-number}"}}]
  (when logger
    (log/info logger :pr-lifecycle :github/linking-fix-pr
              {:message "Linking fix PR to comment"
               :data {:pr-number pr-number :comment-id comment-id
                      :fix-pr-number fix-pr-number}}))
  (let [message (str/replace message-template "#{fix-pr-number}"
                             (str "#" fix-pr-number))
        reply-result (github/reply-to-comment worktree-path pr-number
                                              comment-id message)]
    (if (dag/err? reply-result)
      (reply-failure-result pr-number comment-id fix-pr-number reply-result logger)
      (continue-after-reply worktree-path pr-number comment-id reply-result
                            auto-resolve logger))))
