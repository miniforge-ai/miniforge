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
(ns ai.miniforge.workflow.checkpoint-store-paths
  "Where one workflow run's durable checkpoint state lives on disk.

   Filenames, the per-run directory layout, and root resolution from
   context / opts / config. Nothing here opens a file: callers get a
   path string back and decide what to do with it.

   Split out of `checkpoint-store`, which held path resolution, record
   building and persistence as one five-stratum chain in a single file
   (rule 210 caps a file at three). `checkpoint-store-records` builds
   what gets written; this namespace answers where it goes;
   `checkpoint-store` does the writing."
  (:require
   [ai.miniforge.config.interface :as config]
   [babashka.fs :as fs]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

;; Constants and single-segment path pieces
(def ^{:stratum 0} checkpoint-root-option-key
  "Execution option key for overriding the checkpoint root."
  :checkpoint/root)

(def ^{:stratum 0} machine-snapshot-filename
  "Filename for the authoritative execution-machine snapshot."
  "machine-snapshot.edn")

(def ^{:stratum 0} manifest-filename
  "Filename for the durable workflow checkpoint manifest."
  "manifest.edn")

(def ^{:stratum 0} pin-marker-filename
  "A run is pinned while this file exists in its checkpoint directory: the
   checkpoint GC keeps it. A file of its own rather than a manifest key, so
   pinning never shares a read-modify-write with a checkpoint write."
  "pinned")

(def ^{:stratum 0} pin-marker-temp-prefix
  "Name prefix of a pin marker's temporary file, written and then renamed
   onto the marker. The checkpoint GC's scan skips files named this way:
   they appear and vanish while it runs."
  ".pinned-")

(def ^{:stratum 0} phase-checkpoints-directory-name
  "Directory name for per-phase checkpoint files."
  "phases")

(defn- ^{:stratum 0} normalize-checkpoint-root
  [checkpoint-root]
  (some-> checkpoint-root fs/expand-home str))

(defn ^{:stratum 0} workflow-checkpoint-dir
  "Directory for one workflow run's durable checkpoint state."
  [checkpoint-root workflow-run-id]
  (str (fs/path checkpoint-root (str workflow-run-id))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} pin-marker-temp?
  "True when `path` names a pin marker's temporary file."
  [path]
  (str/starts-with? (str (fs/file-name path)) pin-marker-temp-prefix))

;; Per-run paths and the configured default root
(defn ^{:stratum 1} default-checkpoint-root
  "Default durable checkpoint root from merged config."
  []
  (normalize-checkpoint-root
   (or (get-in (config/load-merged-config) [:workflow :checkpoint-root])
       (str (config/miniforge-home) "/checkpoints"))))

(defn ^{:stratum 1} machine-snapshot-path
  "Path to the authoritative machine snapshot for a workflow run."
  [checkpoint-root workflow-run-id]
  (str (fs/path (workflow-checkpoint-dir checkpoint-root workflow-run-id)
                machine-snapshot-filename)))

(defn ^{:stratum 1} manifest-path
  "Path to the manifest for a workflow run."
  [checkpoint-root workflow-run-id]
  (str (fs/path (workflow-checkpoint-dir checkpoint-root workflow-run-id)
                manifest-filename)))

(defn ^{:stratum 1} pin-marker-path
  "Path to the pin marker of a workflow run."
  [checkpoint-root workflow-run-id]
  (str (fs/path (workflow-checkpoint-dir checkpoint-root workflow-run-id)
                pin-marker-filename)))

(defn ^{:stratum 1} phase-checkpoints-dir
  "Directory that stores per-phase checkpoint files."
  [checkpoint-root workflow-run-id]
  (str (fs/path (workflow-checkpoint-dir checkpoint-root workflow-run-id)
                phase-checkpoints-directory-name)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} resolve-checkpoint-root
  "Resolve checkpoint root from context/opts/config."
  ([]
   (default-checkpoint-root))
  ([m]
   (normalize-checkpoint-root
    (or (:execution/checkpoint-root m)
        (get m checkpoint-root-option-key)
        (get-in m [:execution/opts checkpoint-root-option-key])
        (default-checkpoint-root)))))

(defn ^{:stratum 2} phase-checkpoint-path
  "Path to a persisted phase checkpoint."
  [checkpoint-root workflow-run-id phase-name]
  (str (fs/path (phase-checkpoints-dir checkpoint-root workflow-run-id)
                (str (name phase-name) ".edn"))))
