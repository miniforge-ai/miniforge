;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-branch
  "Puts a restored task branch at the commit its checkpoint recorded. Every
   phase of a task persists to one bundle, so a bundle restore leaves the
   branch at the task's latest persist, not at an earlier checkpoint's."
  (:require [ai.miniforge.dag-executor.interface :as dag]
            [ai.miniforge.workflow.dag-merge-git :as git]
            [ai.miniforge.workflow.messages :as messages]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resolved
  "REV as a full sha in the repository at REPO, or nil when it names nothing."
  [repo rev]
  (let [{:keys [exit out]} (git/run-git repo "rev-parse" "--verify" "--quiet" rev)
        sha (str/trim (str out))]
    (when (and (zero? exit) (seq sha)) sha)))

(defn- ^{:stratum 0} force-branch!
  "`git branch -f`, which refuses a branch another worktree has checked out.
   The refusal is returned, not worked around: moving the ref under a
   checkout would leave its files behind."
  [repo branch sha]
  (let [{:keys [exit err]} (git/run-git repo "branch" "-f" branch sha)]
    (if (zero? exit)
      (dag/ok sha)
      (dag/err :checkpoint-branch-move-failed
               (if (str/blank? err)
                 (messages/system :checkpoint-branch/move-failed {:branch branch :sha sha})
                 (str/trim err))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pin-branch!
  "Put BRANCH at COMMIT-SHA in the host repository at REPO, once
   `rev-parse --verify <sha>^{commit}` finds that commit there. A branch
   already at the commit is left alone, since git refuses to force a
   checked-out branch even onto its own commit. Returns a dag result
   holding the full sha."
  [repo branch commit-sha]
  (let [sha (resolved repo (str commit-sha "^{commit}"))
        tip (resolved repo (str "refs/heads/" branch))]
    (cond
      (nil? sha)  (dag/err :checkpoint-commit-missing
                           (messages/system :checkpoint-branch/commit-missing
                                            {:commit-sha commit-sha :repo repo}))
      (= sha tip) (dag/ok sha)
      :else       (force-branch! repo branch sha))))

(comment ::checkpoint-branch-pin)
