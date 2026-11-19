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
(ns ai.miniforge.web-dashboard.state.workflows.sources
  "Workflow events from the in-memory live stream and from the event
   archive on disk."
  (:require
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.interface :as es]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.event-files :as event-files]
   [ai.miniforge.web-dashboard.state.workflows.events :refer [max-recent-workflows]]
   [ai.miniforge.web-dashboard.watcher :as watcher]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} events-dir-path
  "Default event archive directory shared by the CLI and dashboard."
  (str (config/miniforge-home) "/events"))

(def ^{:stratum 0} ^:private recent-dir-reads
  "{dir-path {:tracked tracking :events [event]}} for the workflow
   directories `historical-events` read last, so a refresh reads only
   the event files added since."
  (atom {}))

(def ^{:stratum 0} disk-retention
  "What `historical-events` keeps of each workflow directory it reads:
   the dashboard stream's own in-memory bound (every lifecycle event, the
   newest health event per dependency, the newest 500 others). A deeper
   history is read from disk per workflow, on demand."
  es/bounded-retention)

(defn ^{:stratum 0} read-event-file
  "Read all parseable EDN events from a workflow event file."
  [file]
  (try
    (when (.exists ^java.io.File file)
      (with-open [reader (io/reader file)]
        (->> (line-seq reader)
             (keep watcher/parse-edn-line)
             vec)))
    (catch Exception _
      [])))

(defn- ^{:stratum 0} compact-into
  "`events` plus the events of `files`, compacted under `policy`. Files
   are decoded a batch at a time, newest first (a batch never splits a
   millisecond), each older batch in front of what is kept, so a long run
   is never held in full. The result is merged with `events` by
   `event-files/order-key` before the last compaction, since a delayed
   file can be older than events already kept."
  [policy events files]
  (es/compact-events
   (->> (event-files/newest-batches files (:max-events-per-workflow policy))
        (reduce (fn [kept batch] (es/compact-events (into batch kept) policy)) [])
        (into events)
        (sort-by event-files/order-key)
        vec)
   policy))

(defn ^{:stratum 0} live-stream-events
  "Read all currently buffered live events from the in-memory event stream."
  [state]
  (try
    (if-let [stream (:event-stream @state)]
      (es/get-events stream)
      [])
    (catch Exception e
      (println "Error reading live workflow events:" (ex-message e))
      [])))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} read-dir
  "[path read] for workflow directory `dir`: only files not read before
   (per `reads`, see `recent-dir-reads`) are decoded, and both the events
   and the file tracking stay bounded by `policy`. A file that turns up
   among those already passed rebuilds the directory's events from all
   its files."
  [reads policy dir]
  (let [path (str dir)
        {:keys [tracked events] :or {events []}} (get reads path)
        [tracked' files rebuild?] (event-files/unseen-files dir tracked
                                                            (:max-events-per-workflow policy))]
    [path {:tracked tracked' :events (compact-into policy (if rebuild? [] events) files)}]))

(defn ^{:stratum 1} workflow-event-file
  "Resolve the event file for a specific workflow-id."
  [workflow-id]
  (io/file events-dir-path (str workflow-id ".edn")))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} historical-events
  "Events from disk (legacy `<workflow-id>.edn` files and file sink
   directories) for one workflow, or, without a workflow-id, for the
   `max-recent-workflows` most recently modified live/ and archived/
   directories: no more than the list shows; the archive scan covers the
   rest. Each directory (not the one-workflow read) is kept within
   `disk-retention`."
  [workflow-id]
  (if workflow-id
    (into (or (read-event-file (workflow-event-file workflow-id)) [])
          (event-files/workflow-events events-dir-path workflow-id))
    (let [edn-file? #(str/ends-with? (.getName ^java.io.File %) ".edn")
          events-dir (io/file events-dir-path)
          reads (into {}
                      (map (partial read-dir @recent-dir-reads disk-retention))
                      (event-files/recent-dirs events-dir-path max-recent-workflows))]
      (reset! recent-dir-reads reads)
      (if (.isDirectory events-dir)
        (into (->> events-dir
                   .listFiles
                   (filter edn-file?)
                   (mapcat read-event-file)
                   vec)
              (mapcat :events)
              (vals reads))
        []))))
