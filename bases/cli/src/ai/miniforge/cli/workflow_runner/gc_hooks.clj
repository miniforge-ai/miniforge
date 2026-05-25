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

(ns ai.miniforge.cli.workflow-runner.gc-hooks
  "Lightweight GC-queue hooks called on workflow lifecycle events.

   Extracted from `workflow-runner` into their own namespace so that:
   1. They can be loaded and tested without pulling in the full runner stack
      (which starts threads and I/O services that hang test JVMs).
   2. The single-responsibility is clear: translate lifecycle events into
      best-effort GC queue I/O with no side-effects on the caller.

   Layer 0: enqueue on workflow finish
   Layer 1: GC pass on workflow start (daemon-free, piggybacked on traffic)"
  (:require
   [ai.miniforge.dag-executor.interface :as gc-queue]
   [ai.miniforge.cli.worktree :as worktree]))

;;------------------------------------------------------------------------------ Layer 0
;; Enqueue hook — called at workflow completion (success or failure)

(defn enqueue-workflow-gc-best-effort!
  "Append `workflow-id` to the scratch-ref GC queue.

   Delegates to `gc-queue/enqueue-workflow-gc!` and swallows any exception —
   GC housekeeping must never interfere with the workflow result or its
   caller's error-handling path."
  [workflow-id]
  (try
    (gc-queue/enqueue-workflow-gc! workflow-id)
    (catch Exception _ nil)))

;;------------------------------------------------------------------------------ Layer 1
;; GC pass hook — called once at each workflow start

(defn run-gc-pass-best-effort!
  "Run the deferred scratch-ref GC pass using the current git repo as the
   parent-repo-path.

   Resolves the repo root via `worktree/worktree-root` (nil when not inside
   a git repo; the call is a no-op in that case).  Never throws — GC is
   best-effort and must not block the workflow that triggers it."
  []
  (try
    (when-let [repo-root (worktree/worktree-root)]
      (gc-queue/run-deferred-gc! repo-root))
    (catch Exception _ nil)))
