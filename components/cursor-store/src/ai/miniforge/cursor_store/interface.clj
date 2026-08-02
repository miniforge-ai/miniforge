(ns ai.miniforge.cursor-store.interface
  "Public API for file-based cursor persistence.

   Cursors are stored as EDN at:
     <pipeline-dir>/.cursors/<pipeline-filename>

   The on-disk map is keyed by [stage-name schema-name] — the only
   identity a cursor entry carries that survives between runs. Both
   `:stage/id` and `:stage/connector-ref` are freshly generated UUIDs
   on every run (see `store/normalize-for-storage`).
   prior-cursor in pipeline-runner looks up by this composite key."
  (:require [ai.miniforge.cursor-store.store :as store]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} save-cursors
  "Persist connector cursors after a pipeline run.
   logger         — logger instance (or nil)
   pipeline-path  — path to the pipeline EDN file (cursor path is derived from it)
   cursor-map     — {stage-uuid → cursor-entry} as returned in :pipeline-run/connector-cursors
   Returns schema/success with :cursors (normalized map) or schema/failure."
  [logger pipeline-path cursor-map]
  (store/save-cursors logger pipeline-path cursor-map))

(defn ^{:stratum 0} load-cursors
  "Load persisted cursors for a pipeline.
   logger         — logger instance (or nil)
   pipeline-path  — path to the pipeline EDN file
   Returns schema/success with :cursors key ({[stage-name schema-name] → cursor-entry}).
   Returns schema/success with empty map on first run.
   Returns schema/failure only on parse errors."
  [logger pipeline-path]
  (store/load-cursors logger pipeline-path))
