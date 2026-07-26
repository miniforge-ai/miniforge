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
(ns ai.miniforge.workflow.dag-merge-git
  "The two git-shell primitives shared across every dag-merge-* namespace
   (miniforge#1317 split). Kept separate so every consumer's own call
   graph stays shallow — a shared leaf like `run-git` contributes zero
   stratification depth to a file that merely requires it, versus
   nonzero depth if it were defined alongside its callers."
  (:require
   [clojure.java.shell :as shell]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-git
  "Invoke git in `cwd` with `args`. Returns `{:exit :out :err}` matching
   `clojure.java.shell/sh`. Defensive against shell exceptions so callers
   can branch on `:exit` rather than wrapping in try/catch."
  [cwd & args]
  (try
    (apply shell/sh "git" "-C" cwd args)
    (catch Exception e
      {:exit -1 :out "" :err (or (ex-message e) (.getName (class e)))})))

(defn ^{:stratum 0} host-repo-path
  "Resolve the host repository path for orchestrator-level git operations.
   Falls back through the standard context keys orchestrators populate."
  [context]
  (or (get context :execution/repo-path)
      (get-in context [:execution/environment-metadata :repo-path])
      (get context :execution/worktree-path)
      (System/getProperty "user.dir")))
