;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-store-generation
  "One checkpoint write's generation directory, `<run>/generations/<id>/`:
   the machine snapshot and the phase checkpoints that write produced.
   Files in a generation are written once and never replaced. The run's
   manifest, published after them, names the files that make up the
   current checkpoint, so a write that dies before its manifest leaves
   an unreferenced generation and the previous checkpoint whole.

   Pruning keeps every generation that the new manifest or the one it
   replaced names: a reader that has just read the previous manifest
   still finds its files. Files outside `generations/` (a run written
   before generations existed) are never pruned."
  (:require [ai.miniforge.workflow.boundary.checkpoint-directory :as directory]
            [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
            [babashka.fs :as fs]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} generations-directory-name
  "Directory under a run's checkpoint directory holding its generations."
  "generations")

(defn- ^{:stratum 0} generation-id
  "A new generation's directory name: creation time first, so a listing
   sorts oldest first, then a random suffix, so two writes never share one."
  []
  (format "%013d-%s" (System/currentTimeMillis) (subs (str (random-uuid)) 0 8)))

(defn- ^{:stratum 0} generation-name
  "The name of the generation directory under `generations` (absolute)
   holding `path`; nil for a file outside it, or a relative path (written
   by a version that recorded the root as given)."
  [generations path]
  (when (fs/absolute? path)
    (let [parts (mapv str (fs/components (fs/relativize generations (fs/path path))))]
      (when (and (< 1 (count parts)) (not= ".." (first parts)))
        (first parts)))))

(defn ^{:stratum 0} snapshot-path
  "Where generation directory `generation` keeps the machine snapshot."
  [generation]
  (str (fs/path generation checkpoint-paths/machine-snapshot-filename)))

(defn ^{:stratum 0} phase-path
  "Where generation directory `generation` keeps `phase-name`'s checkpoint."
  [generation phase-name]
  (str (fs/path generation checkpoint-paths/phase-checkpoints-directory-name
                (str (name phase-name) ".edn"))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create!
  "A new, empty, private generation directory under run directory
   `run-dir`, as its resolved path."
  [run-dir]
  (directory/prepare! (fs/path run-dir generations-directory-name (generation-id))))

(defn- ^{:stratum 1} referenced
  "Names of the generations under `generations` that `manifest` names a
   file in."
  [generations manifest]
  (into #{}
        (comp (remove nil?) (keep #(generation-name generations %)))
        (cons (:workflow/machine-snapshot-path manifest)
              (vals (:workflow/phase-checkpoints manifest)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} prune!
  "Remove every generation under run directory `run-dir` that none of
   `manifests` names a file in. Returns the names removed."
  [run-dir manifests]
  (let [generations (fs/path run-dir generations-directory-name)
        kept (into #{} (mapcat #(referenced generations %)) manifests)]
    (if (fs/directory? generations {:nofollow-links true})
      (into []
            (comp (remove #(kept (str (fs/file-name %))))
                  (map (fn [generation]
                         (fs/delete-tree generation)
                         (str (fs/file-name generation)))))
            (fs/list-dir generations))
      [])))

(comment ::checkpoint-generations)
