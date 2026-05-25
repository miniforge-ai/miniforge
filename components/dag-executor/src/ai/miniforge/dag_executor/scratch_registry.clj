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

(ns ai.miniforge.dag-executor.scratch-registry
  "Lightweight atom-based registry tracking active and released worktrees.

   The registry maps workflow-id → entry so the GC pass and recovery CLI
   can enumerate scratch refs without hitting git directly for listing.

   Entry shape:
     {:scratch-ref   string     — fully-qualified ref (refs/miniforge/scratch/<id>)
      :worktree-path string     — absolute filesystem path of the worktree
      :created-at    Instant    — when register-worktree! was called
      :status        keyword    — :active or :released
      :released-at   Instant}   — set by release-worktree!; absent while :active

   Lifecycle contract:
   - acquire-environment!   → register-worktree!
   - release-environment!   → release-worktree!  (preserves the ref for GC)
   - gc-scratch-refs!       → deletes git refs independently of this registry"
  (:require
   [ai.miniforge.dag-executor.scratch-commit :as scratch-commit]))

;; ============================================================================
;; Registry atom
;; ============================================================================

(def ^:private registry
  "Global worktree lifecycle registry atom.
   Modified only through the public functions below; never reset! in
   production code — use reset-registry! in tests only."
  (atom {}))

;; ============================================================================
;; Public API
;; ============================================================================

(defn register-worktree!
  "Register a newly-created worktree in the lifecycle registry.

   Called from the worktree executor's acquire-environment! path immediately
   after a worktree is created. An existing :released entry for the same
   workflow-id is replaced by a fresh :active entry so reruns don't leak
   stale status from a prior run.

   Arguments:
   - workflow-id   Unique workflow identifier (string)
   - worktree-path Absolute filesystem path to the worktree directory

   Returns the registry entry map that was stored."
  [workflow-id worktree-path]
  (let [entry {:scratch-ref   (scratch-commit/scratch-ref-name workflow-id)
               :worktree-path worktree-path
               :created-at    (java.time.Instant/now)
               :status        :active}]
    (swap! registry assoc workflow-id entry)
    entry))

(defn release-worktree!
  "Mark a worktree's registry entry as :released without deleting the scratch ref.

   Called from the worktree executor's release-environment! path after the
   worktree directory is torn down. The scratch ref survives so GC (via
   gc-scratch-refs!) can inspect commit age and delete when ready.

   A :released entry is NOT removed from the registry — it remains as an
   audit trail until reset-registry! is called (tests) or the process exits.

   Arguments:
   - workflow-id Unique workflow identifier (string)

   Returns the updated entry map, or nil when the workflow-id was not registered."
  [workflow-id]
  (let [now (java.time.Instant/now)]
    (get (swap! registry update workflow-id
                (fn [entry]
                  (when entry
                    (assoc entry
                           :status      :released
                           :released-at now))))
         workflow-id)))

(defn worktree-entry
  "Return the registry entry for `workflow-id`, or nil when not registered."
  [workflow-id]
  (get @registry workflow-id))

(defn registered-worktrees
  "Return a snapshot of all entries (both :active and :released) in the registry.
   Returns a map of workflow-id → entry."
  []
  @registry)

(defn active-worktrees
  "Return a map of workflow-id → entry for all currently :active worktrees."
  []
  (into {} (filter (fn [[_ v]] (= :active (:status v))) @registry)))

(defn reset-registry!
  "Reset the registry to an empty state.

   FOR TESTING ONLY — do not call in production code. Use this in test
   fixtures to isolate tests from each other."
  []
  (reset! registry {}))

;; ============================================================================
;; Rich Comment
;; ============================================================================
(comment
  ;; Register a worktree on acquire
  (register-worktree! "wf-2026-05-25-abc" "/Users/chris/.miniforge/worktrees/task-abc")
  ;=> {:scratch-ref "refs/miniforge/scratch/wf-2026-05-25-abc"
  ;    :worktree-path "/Users/chris/.miniforge/worktrees/task-abc"
  ;    :created-at #inst "..." :status :active}

  ;; Check active worktrees
  (active-worktrees)
  ;=> {"wf-2026-05-25-abc" {:status :active ...}}

  ;; Release on worktree destroy (scratch ref is preserved for GC)
  (release-worktree! "wf-2026-05-25-abc")
  ;=> {:status :released :released-at #inst "..." ...}

  ;; All entries (audit trail)
  (registered-worktrees)
  ;=> {"wf-2026-05-25-abc" {:status :released ...}}

  :leave-this-here)
