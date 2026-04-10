(ns ai.miniforge.data-foundry.pipeline-pack-store.protocol
  "Protocol definition for pipeline pack persistence.")

(defprotocol PackStore
  (save-pack [this pack]
    "Persist a pack manifest and its metrics. Returns pack-id.")

  (load-pack [this pack-id]
    "Retrieve a pack by id. Returns nil if not found.")

  (list-packs [this]
    "List all stored packs. Returns vector of pack manifests.")

  (save-snapshot [this snapshot]
    "Save a metric value snapshot. Returns snapshot-id.")

  (latest-snapshots [this pack-id]
    "Get the latest snapshot for each metric in a pack. Returns vector of snapshots.")

  (save-run [this run]
    "Save a pipeline run record. Returns run-id.")

  (runs-for-pack [this pack-id]
    "Get all runs for a pack. Returns vector of run records.")

  (close [this]
    "Close the store and release resources."))
