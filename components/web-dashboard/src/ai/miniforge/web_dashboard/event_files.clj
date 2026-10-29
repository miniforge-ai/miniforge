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

(defn ^{:stratum 0} file-ms
  "The epoch ms the sink recorded in an event file's name, for either
   name grammar; nil when the file is not an event file (a manifest,
   snapshot, temp or other dot file). The one filter and ordering key of
   this namespace."
  [^File f]
  (es/event-file-ms (.getName f)))

(defn ^{:stratum 0} read-event
  "Decode one event file. nil when it cannot be read or holds no
   :event/type. The reader returns UUIDs as strings; :event/id and
   :workflow/id come back as UUIDs, the form a live event carries, so
   events from both sources group and dedupe as one."
  [f]
  (let [event (es/read-event-file f)
        as-uuid #(or (when (string? %) (parse-uuid %)) %)]
    (when (:event/type event)
      (cond-> event
        (contains? event :event/id)    (update :event/id as-uuid)
        (contains? event :workflow/id) (update :workflow/id as-uuid)))))

(defn ^{:stratum 0} roots
  "The file sink's per-workflow roots under `events-dir`, live first."
  [events-dir]
  [(es/live-dir events-dir) (es/archived-dir events-dir)])

(defn ^{:stratum 0} take-groups
  "[files groups]: the files of the leading `groups` that cover at least
   `size` files, and the groups left."
  [groups size]
  (loop [taken [] groups groups]
    (if (or (>= (count taken) size) (empty? groups))
      [taken groups]
      (recur (into taken (first groups)) (rest groups)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} event-files
  "The event files directly in `dir`, in creation order (recorded ms, then
   name). Sidecars, temp and dot files are left out before any caller
   counts or cuts the list. Empty when `dir` does not exist."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter file-ms)
       (sort-by (juxt file-ms file-name))))

(defn ^{:stratum 1} workflow-dirs
  "The workflow directories under the live and archived roots, live first."
  [events-dir]
  (mapcat #(remove hidden? (.listFiles ^File %)) (roots events-dir)))

(defn ^{:stratum 1} read-ordered
  "Decode `files` and return their events in creation order: by recorded
   ms, then :event/sequence-number (files from one millisecond)."
  [files]
  (->> files
       (keep (fn [f] (some->> (read-event f) (vector (file-ms f)))))
       (sort-by (fn [[ms event]] [ms (get event :event/sequence-number 0)]))
       (mapv second)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unseen-files
  "[names files] for workflow directory `dir`: the names of all its event
   files, and the event files whose names are not in `seen`, in creation
   order. The sink names each file after its event, so a name read once
   need not be read again; a file that turns up late is read whatever
   its name."
  [dir seen]
  (let [files (event-files dir)]
    [(into #{} (map file-name) files)
     (filterv #(not (contains? seen (file-name %))) files)]))

(defn ^{:stratum 2} workflow-events
  "Every event of `workflow-id` in the sink layout (its archived, live or
   legacy flat directory), in creation order. Empty when none exists."
  [events-dir workflow-id]
  (if-let [dir (es/workflow-events-dir events-dir workflow-id)]
    (read-ordered (event-files dir))
    []))

(defn ^{:stratum 2} newest-batches
  "The events of `files` (a directory's event files, in creation order)
   in batches, newest batch first. A batch holds at least `size` files
   and is cut only between files recorded in different milliseconds, so
   a same-millisecond group is never split; its events are in creation
   order (ms, then :event/sequence-number). Lazy: a reader of the newest
   `size` events decodes only the first batch, then takes the last
   `size` of it."
  [files size]
  (->> [[] (reverse (partition-by file-ms files))]
       (iterate (fn [[_ groups]] (take-groups groups size)))
       rest
       (take-while (comp seq first))
       (map (comp read-ordered first))))

(defn ^{:stratum 2} recent-dirs
  "The `n` most recently modified workflow directories, newest first."
  [events-dir n]
  (->> (workflow-dirs events-dir)
       (map (juxt identity mtime))
       (sort-by second >)
       (take n)
       (map first)))
