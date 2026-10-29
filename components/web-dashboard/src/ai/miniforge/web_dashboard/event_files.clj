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
   :event/type (the manifest, the snapshot). The reader returns UUIDs as
   strings; :event/id and :workflow/id come back as UUIDs, the form a
   live event carries, so events from both sources group and dedupe as
   one."
  [f]
  (let [event (es/read-event-file f)
        as-uuid #(or (when (string? %) (parse-uuid %)) %)]
    (when (:event/type event)
      (cond-> event
        (contains? event :event/id)    (update :event/id as-uuid)
        (contains? event :workflow/id) (update :workflow/id as-uuid)))))

(defn ^{:stratum 0} file-tick
  "What orders an event file by creation: the millisecond timestamp of a
   legacy `{yyyyMMddTHHmmssSSSZ}-{uuid}.json` name (its random UUID part
   does not order anything), else the whole name (a Snowflake name is
   unique and sorts in creation order)."
  [^File f]
  (let [n (.getName f)]
    (or (second (re-find #"^(\d{8}T\d{9}Z)-" n)) n)))

(defn ^{:stratum 0} roots
  "The file sink's per-workflow roots under `events-dir`, live first: a
   directory moving to archived/ is then seen in at least one of them."
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

(defn ^{:stratum 0} unread?
  "True when file name `n` is not covered by `tracked` (see `unseen-files`)."
  [{:keys [names floor]} n]
  (and (not (contains? names n))
       (or (nil? floor) (pos? (compare n floor)))))

;------------------------------------------------------------------------------ Layer 1

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

(defn ^{:stratum 1} root-mtimes
  "Last-modified times of the roots; one changes when a workflow
   directory is created, moved or removed."
  [events-dir]
  (mapv mtime (roots events-dir)))

(defn ^{:stratum 1} read-ordered
  "Decode `files` and return their events in creation order: by name and,
   among legacy names from the same millisecond, by :event/sequence-number."
  [files]
  (->> files
       (keep (fn [f] (some->> (read-event f) (vector (file-tick f)))))
       (sort-by (fn [[tick event]] [tick (get event :event/sequence-number 0)]))
       (mapv second)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unseen-files
  "[tracked files] for workflow directory `dir`: its event files not
   covered by `tracked`, in name order, and the tracking state after them.
   Tracking keeps the newest `keep-names` names and counts every name
   sorting below them as read, so it stays bounded for a long run: a file
   appears moments after the name it sorts by, long before that many newer
   ones. With no tracking yet, the files modified at or after `since-ms`
   are the unseen ones."
  ([dir tracked keep-names]
   (unseen-files dir tracked keep-names 0))
  ([dir tracked keep-names since-ms]
   (let [files (event-files dir)
         kept  (take-last keep-names files)]
     [{:names (into #{} (map file-name) kept)
       :floor (some-> (first kept) file-name)}
      (if tracked
        (filterv #(unread? tracked (file-name %)) files)
        (filterv #(>= (mtime %) since-ms) files))])))

(defn ^{:stratum 2} active-dirs
  "{name dir} of the workflow directories modified at or after
   `since-ms`. A name under both roots maps to its archived directory."
  [events-dir since-ms]
  (into {}
        (comp (filter #(>= (mtime %) since-ms))
              (map (juxt file-name identity)))
        (workflow-dirs events-dir)))

(defn ^{:stratum 2} workflow-events
  "Every event of `workflow-id` in the sink layout (its archived, live or
   legacy flat directory), in creation order. Empty when none exists."
  [events-dir workflow-id]
  (if-let [dir (es/workflow-events-dir events-dir workflow-id)]
    (read-ordered (event-files dir))
    []))

(defn ^{:stratum 2} newest-batches
  "The events of `files` (a directory's event files, in name order) in
   batches, newest batch first. A batch holds at least `size` files and
   is cut only between files named in different milliseconds, so a
   same-millisecond group is never split; its events are in creation
   order (millisecond, then :event/sequence-number). Lazy: a reader of
   the newest `size` events decodes only the first batch, then takes the
   last `size` of it."
  [files size]
  (->> [[] (reverse (partition-by file-tick files))]
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
