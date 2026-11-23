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
(ns ai.miniforge.cli.main.commands.hook-eval
  "`mf hook-eval --role <role> --root <worktree>`: the Claude PreToolUse
   hook. The agent component evaluates the call; this namespace hands it
   pr-lifecycle's worktree path guard, which the agent component cannot
   require because pr-lifecycle depends on it."
  (:require
   [ai.miniforge.agent.interface :as agent]
   [ai.miniforge.cli.main.util :as util]
   [ai.miniforge.pr-lifecycle.interface :as pr-lifecycle]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} worktree-path-ok?
  "True when `relative-path` stays inside the worktree at `root` and out of
   its git metadata (git dir, common dir, hooks dir, a `.git` component),
   per pr-lifecycle's `safe-worktree-path` from #2100. The root itself
   passes, for a search of the whole worktree. The path arrives already
   canonical (`tool-policy/worktree-relative` resolves symlinks first), so
   a link inside the worktree is judged where it points, and a dangling
   link is refused."
  [root relative-path]
  (some? (pr-lifecycle/safe-worktree-path root relative-path {:allow-root? true})))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} hook-eval-opts
  "hook-eval's options from the parsed CLI options: the session's :role
   and :root, with `worktree-path-ok?` as the path check."
  [opts]
  (assoc (select-keys opts [:role :root]) :path-ok? worktree-path-ok?))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} hook-eval-cmd
  "Evaluate one PreToolUse request from stdin and exit with hook-eval's
   code: 0 after a reply, 2 (Claude blocks the call) when evaluation
   fails."
  [m]
  (System/exit (agent/hook-eval-stdin! (hook-eval-opts (util/get-opts m)))))
