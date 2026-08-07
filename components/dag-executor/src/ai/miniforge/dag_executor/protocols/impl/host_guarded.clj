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
(ns ai.miniforge.dag-executor.protocols.impl.host-guarded
  "Wraps a TaskExecutor whose environments share a git common dir with the
   host checkout, and holds it to the host-git invariants.

   The worktree executor provisions each task as a linked `git worktree` of
   the host checkout. That isolates the working tree and nothing else:
   `.git/config` and every ref outside HEAD are shared, so an agent running
   `git remote set-url` or `git fetch` inside its task worktree writes the
   host. This wrapper snapshots the shared state at acquire, re-reads it at
   release, and reports the difference.

   Detection, not prevention — see `host-git-guard`. Drift fails the
   release that observed it, warns on stderr, and leaves a verdict against
   the checkout that every later acquire warns about again. It does not
   refuse those later acquires: in `:local` mode a refused acquire is read
   further up as `no environment was configured` and the run proceeds
   against the host with no sandbox at all, which is worse than the drift.
   `host-guarded.lifecycle/acquire!` carries the detail.

   The verdict exists because the 2026-08-06 damage was invisible and the
   branches cut from the corrupted checkout afterwards inherited it. A
   checkout that has drifted keeps saying so until an operator repairs it
   and clears the verdict.

   The acquire and release policy, and the state it needs, live in
   `host-guarded.lifecycle`."
  (:require
   [ai.miniforge.dag-executor.protocols.executor :as proto]
   [ai.miniforge.dag-executor.protocols.impl.host-guarded.lifecycle :as lifecycle]))

;------------------------------------------------------------------------------ Layer 0

;; Executor wrapper
(defrecord ^{:stratum 0} HostGuardedExecutor [delegate]
  proto/TaskExecutor

  (executor-type [_this] (proto/executor-type delegate))

  (available? [_this] (proto/available? delegate))

  (acquire-environment! [_this task-id env-config]
    (lifecycle/acquire! delegate task-id env-config))

  (execute! [_this environment-id command opts]
    (proto/execute! delegate environment-id command opts))

  (copy-to! [_this environment-id local-path remote-path]
    (proto/copy-to! delegate environment-id local-path remote-path))

  (copy-from! [_this environment-id remote-path local-path]
    (proto/copy-from! delegate environment-id remote-path local-path))

  (release-environment! [_this environment-id]
    (lifecycle/release! delegate environment-id))

  (environment-status [_this environment-id]
    (proto/environment-status delegate environment-id))

  (persist-workspace! [_this environment-id opts]
    (proto/persist-workspace! delegate environment-id opts))

  (restore-workspace! [_this environment-id opts]
    (proto/restore-workspace! delegate environment-id opts)))

(def ^{:stratum 0} reset-guard-state!
  "Drop every parked snapshot and drift verdict. See
   `host-guarded.lifecycle/reset-state!` for when that is the right thing
   to do."
  lifecycle/reset-state!)

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} guard-host-git
  "Wrap `delegate` so its environments are held to the host-git invariants.

   Reports the delegate's own `executor-type`, so selection code that
   branches on `:worktree` — the governed-mode capsule check in the
   workflow runner does — keeps seeing what it saw before."
  [delegate]
  (->HostGuardedExecutor delegate))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Wrap the worktree executor; a run that leaves the host's remotes and
  ;; remote-tracking refs alone releases exactly as the unwrapped executor
  ;; would, and the wrapper still selects as :worktree.
  (require '[ai.miniforge.dag-executor.protocols.impl.worktree :as worktree])
  (def guarded (guard-host-git (worktree/create-worktree-executor {})))
  (proto/executor-type guarded)
  ;=> :worktree

  (reset-guard-state!)

  :leave-this-here)
