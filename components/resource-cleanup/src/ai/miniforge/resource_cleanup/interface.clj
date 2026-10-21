;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.resource-cleanup.interface
  "Exception policy at owned-resource effect boundaries, not domain control flow."
  (:require [ai.miniforge.resource-cleanup.boundary.critical :as critical]
            [ai.miniforge.resource-cleanup.boundary.operations :as operations]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fatal-cause
  "Find Error or InterruptedException in a cause chain; tolerate cycles and nil."
  critical/cause)

(def ^{:stratum 0} propagate!
  "Rethrow the original Throwable, restoring the thread flag for interruption."
  critical/propagate!)

(def ^{:stratum 0} after-failure!
  "Attempt cleanup after a captured Throwable, then propagate with fatal priority.
   Preserve structured wrappers and retain independent failures as suppressed.
   Clear an existing interrupt during cleanup, restoring it afterward."
  operations/after-failure!)

(def ^{:stratum 0} finish!
  "Run operation then cleanup even if the operation throws. Ignore return values;
   return nil on success, otherwise propagate with fatal priority and no cycles.
   These are throwing effect ports; returned anomaly values are not exceptions.
   Cleanup runs with a pre-existing interrupt temporarily cleared and restored."
  operations/finish!)

(comment ::owned-resource-failure-policy)
