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
  "Lightweight, pure GC-queue hooks called on workflow lifecycle events.

   ## Design rationale

   Extracted from `workflow-runner` into their own namespace for two reasons:

   1. **Testability without hangs**: `workflow-runner` starts threads and I/O
      services at load time that never terminate in a test JVM.  This namespace
      has *zero* external requires — it accepts all side-effecting dependencies
      as injected function arguments.  Tests can therefore load it safely.

   2. **Single responsibility**: translate lifecycle events into best-effort GC
      queue I/O; no coupling to the caller's error-handling path.

   ## Public API

   Both functions accept their side-effecting collaborators as first-class
   function arguments (dependency injection).  The concrete implementations
   are bound by the caller (`workflow-runner`) via `partial`:

     (partial enqueue-workflow-gc-best-effort! gc-queue/enqueue-workflow-gc!)
     (partial run-gc-pass-best-effort! worktree/worktree-root gc-queue/run-deferred-gc!)

   Layer 0: enqueue on workflow finish (accepts enqueue-fn)
   Layer 1: GC pass on workflow start (accepts worktree-root-fn + gc-fn)")

;;------------------------------------------------------------------------------ Layer 0
;; Enqueue hook — called at workflow completion (success or failure)

(defn enqueue-workflow-gc-best-effort!
  "Append `workflow-id` to the scratch-ref GC queue via `enqueue-fn`.

   `enqueue-fn` — a 1-arity function that accepts the workflow-id string and
   returns a result map.  The concrete implementation supplied by
   `workflow-runner` is `gc-queue/enqueue-workflow-gc!`.

   Swallows any exception — GC housekeeping must never interfere with the
   workflow result or its caller's error-handling path."
  [enqueue-fn workflow-id]
  (try
    (enqueue-fn workflow-id)
    (catch Exception _ nil)))

;;------------------------------------------------------------------------------ Layer 1
;; GC pass hook — called once at each workflow start

(defn run-gc-pass-best-effort!
  "Run the deferred scratch-ref GC pass using the current git repo root.

   `worktree-root-fn` — a 0-arity function that returns the parent git repo
   path string (or nil when not inside a repo).  The concrete implementation
   supplied by `workflow-runner` is `worktree/worktree-root`.

   `gc-fn` — a 1-arity function that accepts the repo path and runs the GC
   pass.  The concrete implementation is `gc-queue/run-deferred-gc!`.

   The call is a no-op when `worktree-root-fn` returns nil (not in a git repo).
   Never throws — GC is best-effort and must not block the workflow that
   triggers it."
  [worktree-root-fn gc-fn]
  (try
    (when-let [repo-root (worktree-root-fn)]
      (gc-fn repo-root))
    (catch Exception _ nil)))
