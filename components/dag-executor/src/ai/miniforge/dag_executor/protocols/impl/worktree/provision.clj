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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.provision
  "Creating a task worktree with `git worktree add`, serialized across
   threads, with branch-retry and detached-HEAD fallbacks."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.worktree :as worktree]
   [ai.miniforge.dag-executor.protocols.impl.worktree.paths :as paths]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private worktree-lock
  "JVM-level lock for git worktree add commands.
   Git uses a config.lock file internally — concurrent worktree adds from
   multiple threads hit this lock and fail silently. Serializing creation at
   the JVM level prevents the conflict. Tasks still run in parallel once their
   worktrees are acquired.

   `check-legacy-tmp-worktrees!` runs inside this lock so that at most one
   thread emits the advisory warning at a time, avoiding duplicated log lines
   in high-parallelism scenarios where all default concurrent slots start
   simultaneously."
  (Object.))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-worktree
  "Create a git worktree for the task.

   Serializes creation through worktree-lock to prevent concurrent git
   config.lock conflicts when multiple sub-workflows acquire environments
   simultaneously. The legacy /tmp worktree check also runs inside the lock so
   at most one thread emits the advisory warning at a time.

   Attempts in order:
   1. git worktree add -b <name> <path> <sha-or-ref>
   2. Clean up stale branch/directory and retry step 1
   3. git worktree add --detach <path> — detached HEAD (last resort)

   Detached HEAD is a real fallback path here, but downstream `archive-bundle!`
   handles it defensively by creating a real branch at HEAD before bundling.
   This one-two punch keeps `git worktree add` failures from cascading into
   broken bundles whose only ref is a bare HEAD."
  [base-path repo-path worktree-name branch]
  (locking worktree-lock
    (paths/check-legacy-tmp-worktrees!)
    (let [worktree-path (str base-path "/" worktree-name)
          base-sha      (worktree/resolve-branch-sha repo-path branch)
          base-ref      (or base-sha branch)]
      (paths/ensure-directory base-path)
      (let [result (worktree/run-git "-C" repo-path
                            "worktree" "add" "-b" worktree-name
                            worktree-path base-ref)]
        (if (zero? (:exit result))
          (result/ok {:worktree-path worktree-path
                      :base-sha base-sha
                      :base-ref branch})
          ;; Failed — clean up stale branch/directory from a prior run and retry
          (do
            (worktree/run-shell "rm" "-rf" worktree-path)
            (worktree/run-git "-C" repo-path "worktree" "prune")
            (worktree/run-git "-C" repo-path "branch" "-D" worktree-name)
            (let [result2 (worktree/run-git "-C" repo-path
                                   "worktree" "add" "-b" worktree-name
                                   worktree-path base-ref)]
              (if (zero? (:exit result2))
                (result/ok {:worktree-path worktree-path
                            :base-sha base-sha
                            :base-ref branch})
                ;; Still failing — detached HEAD as last resort
                (let [result3 (worktree/run-git "-C" repo-path
                                       "worktree" "add" "--detach"
                                       worktree-path)]
                  (if (zero? (:exit result3))
                    (result/ok {:worktree-path worktree-path
                                :base-sha base-sha
                                :base-ref branch})
                    (result/err :worktree-create-failed (:err result3))))))))))))
