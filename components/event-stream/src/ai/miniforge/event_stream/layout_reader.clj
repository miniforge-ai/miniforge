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
(ns ai.miniforge.event-stream.layout-reader
  "Read the file sink's per-workflow directories from another process:
   one transit-JSON file per event under `{events-dir}/live/<id>/`, moved
   to `archived/<id>/` by an archive. Event files are the names
   `sinks/event-file-ms` parses, ordered by that time, then
   :event/sequence-number; :event/id and :workflow/id come back as UUIDs,
   as a live event carries them. Also what a reader tracks of a run: a
   watermark and the names read above it (`track`)."
  (:require
   [ai.miniforge.event-stream.reader :as reader]
   [ai.miniforge.event-stream.sinks :as sinks]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [slingshot.slingshot :refer [try+]])
  (:import
   [java.io File]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} file-name
  [^File f]
  (.getName f))

(defn ^{:stratum 0} mtime
  [^File f]
  (.lastModified f))

(defn ^{:stratum 0} file-ms
  "The epoch ms an event file's name records; nil for any other file."
  [^File f]
  (sinks/event-file-ms (.getName f)))

(defn ^{:stratum 0} read-event
  "Decode one event file; nil when unreadable or not an event. Event,
   workflow and artifact ids come back as UUIDs and :event/timestamp as a
   Date, as a live event carries them."
  [f]
  (let [event    (reader/read-event-file f)
        as-uuid  #(or (when (string? %) (parse-uuid %)) %)
        as-date  (fn [s] (try+ (java.util.Date/from (java.time.Instant/parse s))
                               (catch Exception _ s)))
        artifact (fn [a] (if (map? a)
                           (cond-> a
                             (contains? a :artifact/id) (update :artifact/id as-uuid)
                             (contains? a :id)          (update :id as-uuid))
                           (as-uuid a)))]
    (when (:event/type event)
      (cond-> event
        (contains? event :event/id)            (update :event/id as-uuid)
        (contains? event :workflow/id)         (update :workflow/id as-uuid)
        (string? (:event/timestamp event))     (update :event/timestamp as-date)
        (sequential? (:phase/artifacts event)) (update :phase/artifacts (partial mapv artifact))))))

(defn ^{:stratum 0} source-file
  "The file `read-ordered` decoded `event` from."
  [event]
  (::file (meta event)))

(defn ^{:stratum 0} roots
  "The per-workflow roots under `events-dir`, live first: a directory
   moving to archived/ is then seen in at least one of them."
  [events-dir]
  [(sinks/live-dir events-dir) (sinks/archived-dir events-dir)])

(def ^{:stratum 0} name-window-ms
  "How far behind the newest file read a run's tracking keeps names. A
   file recorded further back that has not been read by then (written
   that late, or failing to decode that long) is given up."
  60000)

(defn ^{:stratum 0} current-dir
  "Workflow `wf-name`'s directory now: under archived/ once the run has
   moved there, else `dir`."
  ^File [events-dir wf-name dir]
  (let [archived (io/file (sinks/archived-dir events-dir) wf-name)]
    (if (.isDirectory archived) archived (io/file dir))))

(defn ^{:stratum 0} retire
  "Tracking for a run leaving the active set, after its last read: the
   watermark moves past the newest name read, and the names go."
  [{:keys [watermark names]}]
  {:watermark (reduce max watermark (map inc (vals names)))
   :names     {}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} event-files
  "The event files in `dir` by recorded ms, then name."
  [dir]
  (->> (.listFiles (io/file dir))
       (filter file-ms)
       (sort-by (juxt file-ms file-name))))

(defn ^{:stratum 1} workflow-dirs
  "The workflow directories under the live and archived roots, live first."
  [events-dir]
  (mapcat #(remove (fn [^File d] (str/starts-with? (.getName d) "."))
                   (.listFiles ^File %))
          (roots events-dir)))

(defn ^{:stratum 1} root-mtimes
  "Last-modified times of the roots; one changes when a workflow
   directory is created, moved or removed."
  [events-dir]
  (mapv mtime (roots events-dir)))

(defn ^{:stratum 1} read-ordered
  "Decode `files` and return their events by recorded ms, then
   :event/sequence-number. Each event knows its file (`source-file`)."
  [files]
  (->> files
       (keep (fn [f] (when-let [event (read-event f)]
                       [(file-ms f) (vary-meta event assoc ::file f)])))
       (sort-by (fn [[ms event]] [ms (get event :event/sequence-number 0)]))
       (mapv second)))

(defn ^{:stratum 1} track
  "Tracking `{:watermark ms :names {name ms}}` after `files` were read:
   their names join, and the watermark rises to `name-window-ms` before
   the newest name, dropping the names below it. A run's tracking holds
   about a minute of names however long it goes on."
  [{:keys [watermark names] :as tracked} files]
  (if (empty? files)
    tracked
    (let [names (into names (map (juxt file-name file-ms)) files)
          mark  (reduce max watermark (map #(- % name-window-ms) (vals names)))]
      {:watermark mark
       :names     (into {} (filter #(>= (val %) mark)) names)})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unread-files
  "The event files in `dir` that `tracked` does not cover: recorded at or
   after its watermark, and not among its names. A file name always
   holds one event."
  [dir {:keys [watermark names]}]
  (filterv #(and (>= (file-ms %) watermark)
                 (not (contains? names (file-name %))))
           (event-files dir)))

(defn ^{:stratum 2} active-dirs
  "{name dir} of the workflow directories modified at or after
   `since-ms`. A name under both roots maps to its archived directory."
  [events-dir since-ms]
  (into {}
        (comp (filter #(>= (mtime %) since-ms))
              (map (juxt file-name identity)))
        (workflow-dirs events-dir)))
