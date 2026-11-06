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
   [slingshot.slingshot :refer [throw+ try+]])
  (:import
   (java.lang ProcessHandle)
   (java.util.concurrent TimeUnit)))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} run-git
  "Invoke git in `cwd` with `args`. Returns `{:exit :out :err}` matching
   `clojure.java.shell/sh`. Defensive against shell exceptions so callers
   can branch on `:exit` rather than wrapping in try/catch. An interrupt
   (a cancel) is not a failed command: git and every process it started
   (a merge driver, a hook) are killed and waited for, and the
   InterruptedException is rethrown, so the merge stops there.

   While git runs, its descendants are recorded every 100 ms. A helper
   whose parent exits is re-parented and no longer listed as git's
   descendant, so the kill set is everything recorded plus what is listed
   at the interrupt (the approach of release-executor's `run-bounded`).
   ProcessHandle checks start times, so a recorded pid since reused by an
   unrelated process is not killed."
  [cwd & args]
  (let [proc (try+ (.start (ProcessBuilder. ^java.util.List (into ["git" "-C" cwd] args)))
                   (catch Exception e e))]
    (if (instance? Exception proc)
      {:exit -1 :out "" :err (or (ex-message proc) (.getName (class proc)))}
      (let [^Process proc proc
            out (future (slurp (.getInputStream proc)))
            err (future (slurp (.getErrorStream proc)))
            observed (volatile! #{})
            descendants #(vec (iterator-seq (.iterator (.descendants proc))))]
        (.close (.getOutputStream proc))
        (try+
          (loop []
            (if (.waitFor proc 100 TimeUnit/MILLISECONDS)
              {:exit (.exitValue proc) :out @out :err @err}
              (do (vswap! observed into (descendants))
                  (recur))))
          (catch InterruptedException e
            (let [tree (conj (vec (into @observed (descendants))) (.toHandle proc))]
              (run! #(.destroyForcibly ^ProcessHandle %) tree)
              (run! #(try+ (.get (.onExit ^ProcessHandle %) 5 TimeUnit/SECONDS) (catch Exception _ nil)) tree))
            (throw+ e)))))))

(defn ^{:stratum 0} host-repo-path
  "Resolve the host repository path for orchestrator-level git operations.
   Falls back through the standard context keys orchestrators populate."
  [context]
  (or (get context :execution/repo-path)
      (get-in context [:execution/environment-metadata :repo-path])
      (get context :execution/worktree-path)
      (System/getProperty "user.dir")))
