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

(ns ai.miniforge.pr-lifecycle.policy-eval.reply
  "N13 §2.5 Comment Response Agent — Layer 3b: reply + resolve.

   For every successfully-applied fix, post the diff as a reply on
   the original comment thread and resolve the conversation. The
   per-fix step is best-effort: a thread-id lookup or resolve-call
   failure does NOT mask a successful reply — the caller sees both
   the URL of the reply and the resolve outcome."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]))

(defn- short-sha
  [sha]
  (subs sha 0 (min 10 (count sha))))

(defn- fix-reply-message
  [commit-sha fix]
  (str "Auto-applied in `" (short-sha commit-sha) "`:\n\n"
       "```diff\n"
       "- " (:before fix) "\n"
       "+ " (:after fix) "\n"
       "```\n\n"
       "<sub>Posted by `policy-eval-responder` per N13 §2.5</sub>"))

(defn reply-and-resolve-one!
  "Post the reply on `comment-id`, look up the thread id, resolve it.
   Returns DAG result with `{:reply-posted :resolved :thread-id?}`.
   Resolution is best-effort — if the thread lookup or resolve call
   fails, we still surface the successful reply."
  [worktree-path pr-number commit-sha fix]
  (let [cid (:comment/id fix)
        msg (fix-reply-message commit-sha fix)
        reply-r (github/reply-to-comment worktree-path pr-number cid msg)]
    (if-not (dag/ok? reply-r)
      reply-r
      (let [thread-r (github/get-thread-id worktree-path pr-number cid)]
        (if-not (dag/ok? thread-r)
          (dag/ok {:reply-posted true :resolved false :thread-error (:error thread-r)})
          (let [tid (-> thread-r :data :thread-id)
                resolve-r (github/resolve-conversation worktree-path tid)]
            (dag/ok {:reply-posted true
                     :resolved (dag/ok? resolve-r)
                     :thread-id tid
                     :reply-url (-> reply-r :data :url)})))))))

(defn reply-and-resolve-fixed!
  "For each applied fix, post a reply on its comment thread + resolve.
   Returns a vector of per-fix DAG results."
  [worktree-path pr-number commit-sha applied-fixes]
  (mapv (fn [fix] (reply-and-resolve-one! worktree-path pr-number commit-sha fix))
        applied-fixes))
