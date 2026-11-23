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
(ns ai.miniforge.dag-executor.protocols.impl.worktree.registry
  "In-memory registry of the worktrees the executor created, keyed by
   workflow id."
  (:require
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private worktree-lifecycle-registry
  "Lightweight atom-based registry tracking worktrees created by the executor.

   Structure: {workflow-id {:scratch-ref   str
                             :worktree-path str
                             :created-at    long  ; epoch ms
                             :status        :active | :released
                             :released-at   long  ; epoch ms, only present when :released}}

   This is an in-memory atom that retains all entries (including :released)
   for the lifetime of the process. It is NOT persisted and is NOT pruned by
   `gc-scratch-refs!`. Git-ref cleanup happens independently via
   `gc-scratch-refs!`, which neither reads nor mutates this registry. This
   lets post-mortem tooling inspect which workflows wrote which scratch
   commits even after the worktrees are gone."
  (atom {}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} get-worktree-registry
  "Return a snapshot of the current worktree lifecycle registry.

   Each entry is keyed by workflow-id and contains:
     :scratch-ref    String ref name under refs/miniforge/scratch/<workflow-id>
     :worktree-path  Absolute path to the scratch worktree
     :created-at     Epoch ms when the worktree was acquired
     :status         :active (worktree present) or :released (worktree removed)
     :released-at    Epoch ms of release (only present when :status :released)"
  []
  @worktree-lifecycle-registry)

(defn ^{:stratum 1} register-worktree-entry!
  "Register a new worktree entry in the lifecycle registry.

   Called on worktree creation to record the scratch-ref and worktree path
   associated with a workflow. If an entry already exists for workflow-id,
   it is replaced — creation is idempotent across re-acquisitions."
  [workflow-id worktree-path]
  (swap! worktree-lifecycle-registry
         assoc workflow-id
         {:scratch-ref   (scratch-commit/scratch-ref-name workflow-id)
          :worktree-path worktree-path
          :created-at    (System/currentTimeMillis)
          :status        :active})
  nil)

(defn ^{:stratum 1} release-worktree-entry!
  "Mark a registry entry as :released while preserving the scratch ref.

   Called on worktree destruction. The entry is retained in the registry
   (with :status :released and :released-at epoch ms) so post-mortem tooling
   and GC can inspect it. The underlying git scratch ref is NOT deleted here —
   use gc-scratch-refs! for that."
  [workflow-id]
  (swap! worktree-lifecycle-registry
         (fn [registry]
           (if (contains? registry workflow-id)
             (update registry workflow-id assoc
                     :status      :released
                     :released-at (System/currentTimeMillis))
             registry)))
  nil)

(defn ^{:stratum 1} find-workflow-id-by-worktree
  "Find the workflow-id whose registry entry has the given worktree-path.
   Returns nil when no match is found."
  [worktree-path]
  (some (fn [[wf-id entry]]
          (when (= worktree-path (:worktree-path entry))
            wf-id))
        @worktree-lifecycle-registry))
