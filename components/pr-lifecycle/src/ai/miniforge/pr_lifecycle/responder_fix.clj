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
(ns ai.miniforge.pr-lifecycle.responder-fix
  "One comment group's fix: its workflow spec, the fix run, and the replies
   that resolve its threads. Split out of
   `ai.miniforge.pr-lifecycle.responder` (rule 210)."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.pr-lifecycle.github :as github]
   [babashka.process :as process]))

;------------------------------------------------------------------------------ Layer 0

;; Context gathering
(defn- ^{:stratum 0} fetch-pr-diff
  "Fetch the PR diff once for all comment groups."
  [worktree-path pr-number]
  (let [r (process/sh "gh" "pr" "diff" (str pr-number) :dir worktree-path)]
    (when (zero? (:exit r)) (:out r))))

(defn- ^{:stratum 0} read-file-content
  "Read a file's content from the worktree. Returns nil if missing."
  [worktree-path path]
  (try
    (slurp (str worktree-path "/" path))
    (catch Exception _ nil)))

;; Fix spec generation
(defn ^{:stratum 0} build-fix-spec
  "Build a lightweight workflow spec from review comments on a file.
   Uses :comment-fix workflow (implement + gates only) instead of full SDLC.
   Includes pre-gathered file contents so the explore phase is skipped."
  [{:keys [path description]} context]
  (let [file-content (some #(when (= path (:path %)) (:content %)) (:files context))]
    (cond-> {:spec/title (str "Fix review comments on " path)
             :spec/description (str "Review comments to address on " path ":\n\n" description
                                    (when (:diff context)
                                      (str "\n\n## PR Diff\n```\n" (:diff context) "\n```")))
             :spec/intent {:type :fix :scope [path]}
             :spec/constraints ["Only modify the specific code referenced by the review comments"
                                "Do not change unrelated code"
                                "Maintain existing tests"]
             :workflow/type :comment-fix}
      file-content
      (assoc :task/existing-files [{:path path :content file-content}]))))

;; Reply and resolution
(defn ^{:stratum 0} reply-and-resolve!
  "Reply to a review comment and resolve its conversation thread.
   Returns {:replied? bool :resolved? bool :error string-or-nil}."
  [worktree-path pr-number comment-id message]
  (let [reply-result (github/reply-to-comment worktree-path pr-number comment-id message)
        replied? (dag/ok? reply-result)
        resolve-result (when replied?
                         (let [thread-result (github/get-thread-id worktree-path pr-number comment-id)]
                           (when (dag/ok? thread-result)
                             (github/resolve-conversation
                              worktree-path
                              (get-in thread-result [:data :thread-id])))))
        resolved? (and resolve-result (dag/ok? resolve-result))]
    {:replied? replied?
     :resolved? resolved?
     :error (when-not replied?
              (get-in reply-result [:error :message] "Reply failed"))}))

;; Orchestration primitives
(defn- ^{:stratum 0} fix-succeeded?
  "True when a workflow result indicates success or partial success.
   A DAG workflow may fail overall but still produce PRs for completed tasks."
  [result]
  (and (map? result)
       (not (:error result))
       (or (not= :failed (get result :execution/status))
           (seq (get-in result [:execution/dag-result :pr-infos])))))

(defn- ^{:stratum 0} fix-result
  "Build a normalized fix result map."
  [path comment-ids succeeded? reply-results error]
  {:path path
   :succeeded? succeeded?
   :comment-ids comment-ids
   :replied? (when reply-results (every? :replied? reply-results))
   :resolved? (when reply-results (every? :resolved? reply-results))
   :error error})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} gather-context
  "Gather PR diff and file contents for all comment groups upfront.
   Returns {:diff string :files [{:path :content}]}."
  [worktree-path pr-number groups]
  (let [diff (fetch-pr-diff worktree-path pr-number)
        paths (distinct (map :path groups))
        files (->> paths
                   (map (fn [p] {:path p :content (read-file-content worktree-path p)}))
                   (filter :content)
                   vec)]
    {:diff diff :files files}))

(defn- ^{:stratum 1} run-fix-for-group
  "Run a fix workflow for a comment group. Returns result map or {:error msg}."
  [run-fix-fn group context opts]
  (let [spec (build-fix-spec group context)]
    (try
      (run-fix-fn spec opts)
      (catch Exception e
        {:error (.getMessage e)}))))

(defn- ^{:stratum 1} reply-to-fixed-comments
  "Reply and resolve all comments in a group after a successful fix."
  [worktree-path pr-number comment-ids fix-pr-url]
  (let [message (if fix-pr-url
                  (str "Fixed in " fix-pr-url)
                  "Fixed in latest push. Changes address the review feedback.")]
    (mapv #(reply-and-resolve! worktree-path pr-number % message)
          comment-ids)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} process-comment-group
  "Fix one file's comments, reply on success. Returns fix result map."
  [worktree-path pr-number run-fix-fn context opts {:keys [path comment-ids] :as group}]
  (let [result (run-fix-for-group run-fix-fn group context opts)
        succeeded? (fix-succeeded? result)
        fix-pr-url (first (keep #(get % :pr-url) (get-in result [:execution/dag-result :pr-infos])))]
    (if succeeded?
      (fix-result path comment-ids true
                  (reply-to-fixed-comments worktree-path pr-number comment-ids fix-pr-url) nil)
      (fix-result path comment-ids false nil (get result :error)))))

(comment ::responder-fix)
