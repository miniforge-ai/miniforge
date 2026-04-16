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

(ns ai.miniforge.cli.main.commands.worktree
  "User-facing helpers for resolving and executing inside the correct worktree."
  (:require
   [babashka.process :as process]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.worktree :as worktree]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(defn command-args
  [m]
  (vec (get m :args [])))

(defn command-target
  [opts]
  (get opts :path (System/getProperty "user.dir")))

(defn resolve-root
  [opts]
  (worktree/worktree-root (command-target opts)))

;------------------------------------------------------------------------------ Layer 1
;; Execution

(defn path-cmd
  [opts]
  (let [root (resolve-root opts)
        target (command-target opts)]
    (if root
      (println root)
      (do
        (display/print-error
         (messages/t :worktree/root-not-found {:path target}))
        (System/exit 1)))))

(defn run-cmd
  [opts args]
  (let [root (resolve-root opts)
        target (command-target opts)
        normalized-args (->> args
                             (remove #{"--"})
                             vec)]
    (cond
      (nil? root)
      (do
        (display/print-error
         (messages/t :worktree/root-not-found {:path target}))
        (System/exit 1))

      (empty? normalized-args)
      (do
        (display/print-error
         (messages/t :worktree/run-usage
                     {:command (app-config/command-string "worktree run -- <cmd> [args...]")}))
        (System/exit 1))

      :else
      (let [[cmd & cmd-args] normalized-args
            proc (apply process/process
                        {:out :inherit :err :inherit :dir root}
                        cmd
                        cmd-args)
            exit (get @proc :exit 1)]
        (System/exit exit)))))

(defn worktree-cmd
  [m]
  (let [opts (if (contains? m :opts) (:opts m) m)
        args (command-args m)
        subcommand (first args)
        remaining (vec (rest args))]
    (case subcommand
      "path" (path-cmd opts)
      "run" (run-cmd opts remaining)
      (do
        (display/print-error
         (messages/t :worktree/usage
                     {:command (app-config/command-string "worktree <path|run> [options]")}))
        (System/exit 1)))))
