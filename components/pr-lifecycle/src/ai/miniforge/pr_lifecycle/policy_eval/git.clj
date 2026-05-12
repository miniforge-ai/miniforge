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

(ns ai.miniforge.pr-lifecycle.policy-eval.git
  "N13 §2.5 Comment Response Agent — Layer 3a: git commit + push.

   Stage every touched file, commit with a deterministic message,
   capture the resulting SHA, push. Each step is a `git` subprocess
   returning a DAG result; `commit-and-push!` chains them with
   `dag/when-let-ok` and short-circuits on the first failure."
  (:require
   [ai.miniforge.dag-executor.interface :as dag]
   [babashka.process :as process]
   [clojure.string :as str]))

(defn- git-sh
  "Run `git -C worktree-path <args>`. Returns DAG result."
  [worktree-path & args]
  (try
    (let [r (apply process/shell
                   {:dir (str worktree-path)
                    :out :string :err :string :continue true}
                   "git" "-C" (str worktree-path) args)]
      (if (zero? (:exit r))
        (dag/ok {:output (str/trim (:out r ""))})
        (dag/err :policy-eval/git-failed
                 (str/trim (:err r ""))
                 {:exit-code (:exit r) :args (vec args)})))
    (catch Throwable e
      (dag/err :policy-eval/git-failed (.getMessage e) {:args (vec args)}))))

(defn- stage-paths!
  "git add each unique path in `applied-fixes`."
  [worktree-path applied-fixes]
  (let [paths (distinct (map :path applied-fixes))]
    (reduce
     (fn [acc path]
       (let [r (git-sh worktree-path "add" "--" path)]
         (if (dag/ok? r) acc (reduced r))))
     (dag/ok {:staged-count (count paths)})
     paths)))

(defn- commit-message
  "Build a commit message summarizing the applied fixes."
  [applied-fixes]
  (let [n (count applied-fixes)]
    (str "fix(policy-eval): apply " n " standards-pack auto-fix"
         (when (not= n 1) "es")
         "\n\n"
         (str/join "\n"
                   (for [f applied-fixes]
                     (str "- " (:path f) ":" (:line f))))
         "\n\n"
         "Applied by miniforge pr-lifecycle/policy-eval-responder per N13 §2.5.")))

(defn commit-and-push!
  "Stage all touched files, commit, push. Returns DAG result with
   `{:commit-sha :pushed?}` on success.

   Uses `dag/when-let-ok` (railway-binding macro) — see
   `.cursor/rules/languages/clojure-railway-binding.mdc` (dewey 211).
   Each step short-circuits to its failure result on `(not (ok? r))`."
  [worktree-path applied-fixes]
  (dag/when-let-ok
   [_stage-r  (stage-paths! worktree-path applied-fixes)
    _commit-r (git-sh worktree-path "commit" "-m" (commit-message applied-fixes))
    sha-r     (git-sh worktree-path "rev-parse" "HEAD")
    _push-r   (git-sh worktree-path "push")]
   (dag/ok {:commit-sha (:output (:data sha-r))
            :pushed?    true})))
