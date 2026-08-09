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
(ns ai.miniforge.cli.workflow-runner.context-git
  "Git checkout state for a workflow run: single-field `git` queries,
   the branch/commit pair stamped into a spec's runtime context, and
   the fuller upstream/dirty/detached snapshot stamped into a workflow
   context. Split out of `ai.miniforge.cli.workflow-runner.context`
   (rule 210: the parent namespace measured 4 real layers, max 3; this
   probe chain was its whole depth — same approach as the
   workflow-runner split, miniforge#1662-#1667)."
  (:require
   [clojure.java.shell :as shell]
   [clojure.string :as str]
   [ai.miniforge.cli.worktree :as worktree]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} resolve-git-field
  [dir & args]
  (let [{:keys [exit out]} (apply shell/sh (concat ["git"] args [:dir dir]))]
    (when (zero? exit)
      (some-> out str/trim not-empty))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolve-git-bool
  [dir & args]
  (boolean (apply resolve-git-field dir args)))

(defn ^{:stratum 1} get-git-info
  ([] (get-git-info nil))
  ([start-path]
   (try
     (when-let [root (worktree/worktree-root start-path)]
       (let [branch (resolve-git-field root "rev-parse" "--abbrev-ref" "HEAD")
             commit (resolve-git-field root "rev-parse" "--short" "HEAD")]
         (when (and branch commit)
           {:git-branch branch
            :git-commit commit})))
     (catch Exception _ nil))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} get-git-state
  ([] (get-git-state nil))
  ([start-path]
   (try
     (when-let [root (worktree/worktree-root start-path)]
       (let [branch (resolve-git-field root "rev-parse" "--abbrev-ref" "HEAD")
             commit (resolve-git-field root "rev-parse" "--short" "HEAD")
             upstream (resolve-git-field root "rev-parse" "--abbrev-ref" "--symbolic-full-name" "@{upstream}")
             dirty? (resolve-git-bool root "status" "--porcelain")]
         (when (and branch commit)
           {:git-branch branch
            :git-commit commit
            :git-upstream upstream
            :git-dirty? dirty?
            :git-detached? (= "HEAD" branch)})))
     (catch Exception _ nil))))
