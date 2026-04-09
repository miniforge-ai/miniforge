(ns ai.miniforge.data-foundry.pipeline-pack-store.interface
  "Public API for pipeline pack persistence.

   Uses requiring-resolve to defer Datalevin loading."
  (:require
   [ai.miniforge.data-foundry.pipeline-pack-store.protocol :as proto]))

;; -- Store creation --
(defn create-store
  "Create a pack store. Options:
     :dir - directory for persistent storage (nil = in-memory for tests)"
  ([] ((requiring-resolve 'ai.miniforge.data-foundry.pipeline-pack-store.datalevin-store/create-store)))
  ([opts] ((requiring-resolve 'ai.miniforge.data-foundry.pipeline-pack-store.datalevin-store/create-store) opts)))

;; -- Protocol delegations --
(defn save-pack
  "Persist a pack manifest and its metrics."
  [store pack]
  (proto/save-pack store pack))

(defn load-pack
  "Retrieve a pack by id."
  [store pack-id]
  (proto/load-pack store pack-id))

(defn list-packs
  "List all stored packs."
  [store]
  (proto/list-packs store))

(defn save-snapshot
  "Save a metric value snapshot."
  [store snapshot]
  (proto/save-snapshot store snapshot))

(defn latest-snapshots
  "Get the latest snapshot for each metric in a pack."
  [store pack-id]
  (proto/latest-snapshots store pack-id))

(defn save-run
  "Save a pipeline run record."
  [store run]
  (proto/save-run store run))

(defn runs-for-pack
  "Get all runs for a pack."
  [store pack-id]
  (proto/runs-for-pack store pack-id))

(defn close
  "Close the store and release resources."
  [store]
  (proto/close store))
