;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase.interface.task-base
  "The implement phase's resume-aware task base refs: what the artifact
   collector diffs against and every :no-secrets check measures from.")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} recorded-refs
  "The task base refs CTX already holds: carried as
   :execution/task-base-refs, or recorded as :code/base-refs on an
   implement phase result (its artifact on success, its output on a
   denial), which a resumed run restores from its checkpoint. Nil when
   none was recorded."
  [ctx]
  (or (get ctx :execution/task-base-refs)
      (some (fn [result] (or (get-in result [:artifact :code/base-refs])
                             (get-in result [:result :output :code/base-refs])))
            (vals (get ctx :execution/phase-results)))))
