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

(ns ai.miniforge.workbench-orchestration-adapter.config
  "Pure resolved-run boundary for orchestration workbench snapshots.

   An orchestration run is RESOLVED when its lifecycle projection reached a
   terminal status per N2 §2.2-§2.3: `:completed`, `:failed`, or
   `:cancelled`. Only resolved runs are comparable across minibench
   variants; unresolved (dangling) runs are excluded from snapshots.

   The supervisory-state EntityTable keeps only `:workflow-run/current-phase`
   - not the traversed phase sequence - so the observed phase history is
   derived here as a pure fold over the same N2 §2.4 lifecycle event stream
   the caller already folded into the table.")

;------------------------------------------------------------------------------ Layer 0
;; Resolved-run boundary

(def resolved-run-schema-version "miniforge_orchestration_resolved_run/v1")

(def terminal-statuses
  "Terminal `:workflow-run/status` values per N2 §2.2-§2.3. `:done` is the
   phase machine's terminal node; these are the lifecycle projections a run
   lands on once the machine can make no further transition."
  #{:completed :failed :cancelled})

(defn resolved?
  "True when `run` (a supervisory-state WorkflowRun entity) is at the
   resolved-run boundary - its lifecycle status is terminal."
  [run]
  (contains? terminal-statuses (:workflow-run/status run)))

;------------------------------------------------------------------------------ Layer 1
;; Boundary queries over an EntityTable

(defn resolved-runs
  "All resolved WorkflowRun entities in `table`, ordered by started-at."
  [table]
  (->> (vals (:workflows table))
       (filter resolved?)
       (sort-by :workflow-run/started-at)
       vec))

(defn dangling-run-count
  "Number of workflow runs in `table` that have not reached the boundary."
  [table]
  (count (remove resolved? (vals (:workflows table)))))

;------------------------------------------------------------------------------ Layer 2
;; Observed phase history

(defn phase-history
  "Ordered phases observed for `workflow-id` in `events` - a pure fold over
   the `:workflow/phase-started` lifecycle events (N2 §2.4) that also fed
   the supervisory-state accumulator. Returns a (possibly empty) vector."
  [events workflow-id]
  (into []
        (comp (filter #(= :workflow/phase-started (:event/type %)))
              (filter #(= workflow-id (:workflow/id %)))
              (keep :workflow/phase))
        events))
