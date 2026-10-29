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
(ns ai.miniforge.web-dashboard.event-files
  "Read the event-stream file sink's on-disk layout.

   The sink writes one transit-JSON file per event under
   `{events-dir}/live/<workflow-id>/`; a JVM run's archive moves that
   directory to `{events-dir}/archived/<workflow-id>/`. File names sort
   in creation order. Files are decoded with the event-stream reader.
   Skipped: the sink's temp files (`.es-*.transit.tmp`), any other name
   starting with `.`, and JSON files that are not events (the manifest,
   the snapshot)."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} file-name
  [^File f]
  (.getName f))

(defn ^{:stratum 0} mtime
  [^File f]
  (.lastModified f))

(defn ^{:stratum 0} hidden?
  [^File f]
  (str/starts-with? (.getName f) "."))

(defn ^{:stratum 0} read-event
  "Decode one event file. nil when it cannot be read or holds no
   :event/type (the manifest, the snapshot)."
  [f]
  (let [event (es/read-event-file f)]
    (when (:event/type event) event)))

(defn ^{:stratum 0} roots
  "The file sink's per-workflow roots under `events-dir`, live first."
  [events-dir]
  [(es/live-dir events-dir) (es/archived-dir events-dir)])

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} unseen
  "The files of `files` whose names are not in `seen`."
  [files seen]
  (remove (comp seen file-name) files))

(defn ^{:stratum 1} event-files
  "Event files directly in `dir`, sorted by name. Empty when `dir` does
   not exist."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter #(and (str/ends-with? (file-name %) ".json") (not (hidden? %))))
       (sort-by file-name)))

(defn ^{:stratum 1} workflow-dirs
  "The workflow directories under the live and archived roots, live first."
  [events-dir]
  (mapcat #(remove hidden? (.listFiles ^File %)) (roots events-dir)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-unseen
  "Read the events in `dir` whose file names are not in `seen`. Returns
   [names events]: the names of every event file now in `dir`, and the
   new events in file-name order. The sink names each file after its
   event, so a name read once need not be read again."
  [dir seen]
  (let [files (event-files dir)]
    [(into #{} (map file-name) files)
     (into [] (keep read-event) (unseen files seen))]))

(defn ^{:stratum 2} workflow-events
  "Every event of `workflow-id` in the sink layout (its archived, live or
   legacy flat directory), in file-name order. Empty when none exists."
  [events-dir workflow-id]
  (if-let [dir (es/workflow-events-dir events-dir workflow-id)]
    (into [] (keep read-event) (event-files dir))
    []))

(defn ^{:stratum 2} recent-dirs
  "The `n` most recently modified workflow directories, newest first."
  [events-dir n]
  (->> (workflow-dirs events-dir)
       (map (juxt identity mtime))
       (sort-by second >)
       (take n)
       (map first)))
