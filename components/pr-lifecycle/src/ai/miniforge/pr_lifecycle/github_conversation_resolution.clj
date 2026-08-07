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
(ns ai.miniforge.pr-lifecycle.github-conversation-resolution
  "Fail-soft review-thread resolution after a successful reply."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.logging.interface :as log]
   [ai.miniforge.pr-lifecycle.github :as github]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} reply-outcome
  [reply-result resolved? details]
  (dag/ok (merge {:reply-posted true :resolved resolved?
                  :reply-url (:url (:data reply-result))}
                 details)))

(defn- ^{:stratum 0} log-event
  [logger log-fn event message data]
  (when logger
    (log-fn logger :pr-lifecycle event {:message message :data data})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} thread-lookup-failure-outcome
  [reply-result thread-result comment-id logger]
  (log-event logger log/warn :github/thread-id-failed
             "Could not get thread ID for resolution"
             {:error (:error thread-result) :comment-id comment-id})
  (reply-outcome reply-result false
                 {:resolution-error (:error thread-result)}))

(defn- ^{:stratum 1} already-resolved-outcome
  [reply-result thread-id logger]
  (log-event logger log/info :github/already-resolved
             "Thread already resolved" {:thread-id thread-id})
  (reply-outcome reply-result true
                 {:already-resolved true :thread-id thread-id}))

(defn- ^{:stratum 1} resolve-open-thread
  [worktree-path pr-number reply-result thread-id logger]
  (let [result (github/resolve-conversation worktree-path thread-id)]
    (if (dag/ok? result)
      (do
        (log-event logger log/info :github/conversation-resolved
                   "Conversation resolved successfully"
                   {:thread-id thread-id :pr-number pr-number})
        (reply-outcome reply-result true {:thread-id thread-id}))
      (do
        (log-event logger log/warn :github/resolution-failed
                   "Failed to resolve conversation"
                   {:error (:error result) :thread-id thread-id})
        (reply-outcome reply-result false
                       {:resolution-error (:error result)
                        :thread-id thread-id})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-after-reply
  "Optionally resolve a review thread without discarding reply success."
  [worktree-path pr-number comment-id reply-result auto-resolve logger]
  (let [thread-result (when auto-resolve
                        (github/get-thread-id worktree-path pr-number comment-id))
        thread-id (:thread-id (:data thread-result))]
    (cond
      (not auto-resolve)
      (reply-outcome reply-result false {})

      (dag/err? thread-result)
      (thread-lookup-failure-outcome reply-result thread-result comment-id logger)

      (:is-resolved (:data thread-result))
      (already-resolved-outcome reply-result thread-id logger)

      :else
      (resolve-open-thread worktree-path pr-number reply-result thread-id logger))))
