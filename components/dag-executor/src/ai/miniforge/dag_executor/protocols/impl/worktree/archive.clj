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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.archive
  "Persist a task worktree's work as a git bundle and restore it into
   the host repo: local-tier fidelity for :governed parity."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.protocols.impl.worktree.archive-steps :as archive-steps]
   [ai.miniforge.dag-executor.result :as result]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-commit-message
  "Commit message for the per-phase persist commit when the caller did not
   override `:message`. Internal infrastructure; not user-visible at
   commit time."
  "phase checkpoint")

(def ^{:stratum 0} default-base-ref
  "Final fallback base ref when neither :base-branch nor :base-ref was
   passed by the caller. Used only by direct callers of `archive-bundle!`;
   the runner threads the real base from environment metadata."
  "main")

(defn ^{:stratum 0} restore-from-bundle!
  "Restore work from a previously-persisted bundle into the host repo.

   `git fetch <bundle> <branch>:<branch>` pulls the branch and all reachable
   commits into the host repo without touching the working tree.

   Arguments:
   - host-repo-path: path to a worktree of the host repo
   - opts: {:bundle-path str :branch str}

   Returns result/ok with {:restored? true :branch str} on success."
  [host-repo-path {:keys [bundle-path branch]}]
  (letfn [(failure [fallback command]
            (result/err :archive-restore-failed
                        (or (not-empty (:err command)) fallback)))
          (checked-out-branch []
            (let [restore-ref (str "refs/miniforge/resume/" branch)
                  cleanup     (atom nil)
                  outcome
                  (try
                    (let [fetch-ref (worktree/run-git "-C" host-repo-path "fetch"
                                             bundle-path (str branch ":" restore-ref))]
                      (if-not (zero? (:exit fetch-ref))
                        (failure "git fetch from bundle failed" fetch-ref)
                        (let [branch-sha  (worktree/run-git "-C" host-repo-path "rev-parse" branch)
                              restore-sha (worktree/run-git "-C" host-repo-path "rev-parse" restore-ref)
                              ancestor    (worktree/run-git "-C" host-repo-path "merge-base" "--is-ancestor"
                                                   (str/trim (:out restore-sha))
                                                   (str/trim (:out branch-sha)))]
                          (cond
                            (not (zero? (:exit branch-sha)))
                            (failure "git rev-parse branch failed" branch-sha)

                            (not (zero? (:exit restore-sha)))
                            (failure "git rev-parse resume ref failed" restore-sha)

                            (zero? (:exit ancestor))
                            (result/ok {:restored? true
                                        :branch branch
                                        :bundle-path bundle-path
                                        :already-checked-out? true})

                            (= 1 (:exit ancestor))
                            (result/err :archive-restore-failed
                                        "Checked-out branch does not contain bundled checkpoint commit")

                            :else
                            (failure "git merge-base failed" ancestor)))))
                    (finally
                      (reset! cleanup
                              (worktree/run-git "-C" host-repo-path "update-ref" "-d" restore-ref))))]
              (if (and (result/ok? outcome)
                       (not (zero? (:exit @cleanup))))
                (failure "git cleanup of resume ref failed" @cleanup)
                outcome)))]
    (try
      (let [r (worktree/run-git "-C" host-repo-path "fetch"
                       bundle-path (str branch ":" branch))]
        (if (zero? (:exit r))
          (result/ok {:restored? true :branch branch :bundle-path bundle-path})
          (if (str/includes? (get r :err "")
                             "refusing to fetch into branch")
            (checked-out-branch)
            (failure "git fetch from bundle failed" r))))
      (catch Exception e
        (result/err :archive-restore-failed (.getMessage e))))))

(defn- ^{:stratum 0} commit-and-bundle!
  "Pipeline: stage -> commit-if-dirty -> recompute ahead -> bundle-or-noop."
  [worktree-path branch base-ref archive-dir task-id message]
  (-> (archive-steps/stage-all! worktree-path)
      (result/and-then (fn [_] (archive-steps/maybe-commit worktree-path message)))
      (result/and-then (fn [_] (archive-steps/commits-ahead worktree-path base-ref)))
      (result/and-then
       (fn [ahead]
         (if (zero? ahead)
           (result/ok {:persisted? false :no-changes? true :branch branch})
           (archive-steps/bundle-result worktree-path branch base-ref archive-dir task-id ahead))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} archive-bundle!
  "Persist a worktree task work as a git bundle.

   Pipeline: resolve current branch -> check for dirty changes or commits
   ahead of base-ref -> if clean, return no-changes; otherwise stage,
   commit on the task branch with --no-verify, and write a bundle of
   `base-ref..HEAD` to <archive-dir>/<task-id>.bundle.

   Arguments:
   - worktree-path: absolute path to the scratch worktree
   - opts: {:archive-dir string  -- destination dir for bundles
           :task-id      string  -- identifier used as the bundle filename
           :base-ref     string  -- base branch the worktree was forked from
           :message      string  -- commit message for the persist commit}

   Returns result/ok with one of:
   - {:persisted? true  :bundle-path str :commit-sha str :branch str}
   - {:persisted? false :no-changes? true :branch str}
   Returns result/err on any git failure (no silent fallbacks)."
  [worktree-path {:keys [archive-dir task-id base-ref message]
                  :or   {message  default-commit-message
                         base-ref default-base-ref}}]
  (-> (archive-steps/current-branch worktree-path)
      (result/and-then
       (fn [raw-branch]
         (-> (archive-steps/ensure-named-branch! worktree-path raw-branch task-id)
             (result/and-then
              (fn [branch]
                (-> (archive-steps/already-clean? worktree-path base-ref)
                    (result/and-then
                     (fn [clean?]
                       (if clean?
                         (result/ok {:persisted? false :no-changes? true :branch branch})
                         (commit-and-bundle! worktree-path branch base-ref
                                             archive-dir task-id message))))))))))))
