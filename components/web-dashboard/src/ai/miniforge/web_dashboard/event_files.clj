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
  "Read the file sink's layout: one transit-JSON file per event under
   `{events-dir}/live/<workflow-id>/`, moved to `archived/<workflow-id>/`
   by a JVM run's archive. Decodes with the event-stream reader."
  (:require
   [ai.miniforge.event-stream.interface :as es]
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

(defn ^{:stratum 0} hidden?
  [^File f]
  (str/starts-with? (.getName f) "."))

(defn ^{:stratum 0} file-ms
  "The epoch ms an event file's name records (`es/event-file-ms`); nil
   for any other file. This namespace's one filter and ordering key."
  [^File f]
  (es/event-file-ms (.getName f)))

(defn ^{:stratum 0} canonical-id
  "The form of an event or workflow id every comparison uses, whatever
   its source: a UUID-shaped string as the UUID, a keyword or its
   \":name\" string as the name, anything else as its string."
  [id]
  (cond
    (or (nil? id) (uuid? id)) id
    (keyword? id) (name id)
    :else (let [s (str id)] (or (parse-uuid s) (str/replace s #"^:" "")))))

(defn ^{:stratum 0} read-event
  "Decode one event file; nil when unreadable or not an event. The
   reader returns UUIDs and instants as strings; event, workflow and
   artifact ids come back as UUIDs and :event/timestamp as a Date, as a
   live event carries them."
  [f]
  (let [event    (es/read-event-file f)
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

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} event-files
  "The event files in `dir` by recorded ms, then name; sidecars and temp
   files are left out before any caller counts or cuts the list."
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
   ms, then :event/sequence-number (files from one millisecond). Each
   event carries its file as `::file` metadata."
  [files]
  (->> files
       (keep (fn [f] (when-let [event (read-event f)]
                       [(file-ms f) (vary-meta event assoc ::file f)])))
       (sort-by (fn [[ms event]] [ms (get event :event/sequence-number 0)]))
       (mapv second)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} unseen-files
  "[names files]: the names of all event files in `dir`, and those files
   whose names are not in `seen`. A file name always holds one event.
   With no names yet (`seen` nil), the unseen files are those modified at
   or after `since-ms`."
  ([dir seen]
   (unseen-files dir seen 0))
  ([dir seen since-ms]
   (let [files (event-files dir)]
     [(into #{} (map file-name) files)
      (if seen
        (filterv #(not (contains? seen (file-name %))) files)
        ;; mtime 0: gone since the listing (moved); a caller retries it.
        (filterv #(let [m (mtime %)] (or (>= m since-ms) (zero? m))) files))])))

(defn ^{:stratum 2} workflow-events
  "Every event of `workflow-id` in the sink layout (its archived, live or
   legacy flat directory), in creation order. Empty when none exists."
  [events-dir workflow-id]
  (if-let [dir (es/workflow-events-dir events-dir workflow-id)]
    (read-ordered (event-files dir))
    []))

(defn ^{:stratum 2} newest-batches
  "The events of `files` (from `event-files`), newest batch first, each
   batch at least `size` files, cut only between different recorded ms,
   and ordered by ms then :event/sequence-number. Lazy: the newest `size`
   events are the last `size` of the first batch."
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
