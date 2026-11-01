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
(ns ai.miniforge.web-dashboard.archive
  "Archive scanning — lightweight workflow summaries from the first and
   last events of each legacy `<workflow-id>.edn` file (two seeks) and
   each file sink workflow directory (live/, archived/, the older flat
   layout), without loading every event into memory."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.event-files :as event-files]
   [ai.miniforge.web-dashboard.watcher :as watcher])
  (:import
   [java.io File RandomAccessFile]))

;------------------------------------------------------------------------------ Layer 0

;; Pure summary derivation — data in, data out
(defn ^{:stratum 0} extract-wf-id
  [event]
  (or (:workflow/id event) (:workflow-id event)))

(defn ^{:stratum 0} extract-wf-name
  [event wf-id]
  (or (get-in event [:workflow/spec :spec/title])
      (get-in event [:workflow/spec :title])
      (get-in event [:workflow/spec :name])
      (get-in event [:workflow-spec :title])
      (get-in event [:workflow-spec :name])
      (get-in event [:spec :title])
      (get-in event [:spec :name])
      (str "Workflow " (subs (str wf-id) 0 (min 8 (count (str wf-id)))))))

(defn ^{:stratum 0} extract-timestamp
  [event]
  (or (:event/timestamp event) (:timestamp event)))

(defn ^{:stratum 0} find-by-type
  "First event from coll whose :event/type is in type-set."
  [type-set events]
  (first (filter #(contains? type-set (:event/type %)) events)))

(defn ^{:stratum 0} derive-status
  [terminal-event tail-line-count]
  (cond
    (= :workflow/failed (:event/type terminal-event))
    :failed

    (= :workflow/completed (:event/type terminal-event))
    (case (or (:workflow/status terminal-event) (:status terminal-event))
      (:failure :failed :cancelled) :failed
      :completed)

    (<= tail-line-count 2)
    :stale

    :else :stale))

;; I/O: read first+last lines from event file
(defn ^{:stratum 0} read-first-event
  "Read and parse the first line of a RandomAccessFile."
  [^RandomAccessFile raf]
  (-> (.readLine raf) watcher/parse-edn-line))

(defn ^{:stratum 0} read-tail-events
  "Seek near EOF, read last ~2KB of lines, parse and return most-recent-first."
  [^RandomAccessFile raf ^long length]
  (let [seek-pos (max 0 (- length 2048))]
    (.seek raf seek-pos)
    (when (pos? seek-pos) (.readLine raf))           ; skip partial line
    (->> (loop [lines []]
           (if-let [line (.readLine raf)]
             (recur (conj lines line))
             lines))
         (keep watcher/parse-edn-line)
         reverse
         vec)))

(def ^{:stratum 0} tail-file-count
  "Event files read from the end of a workflow directory for its status."
  10)

;; Archive scan orchestration
(defn ^{:stratum 0} list-edn-files
  [events-dir]
  (let [dir (io/file events-dir)]
    (when (and (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName ^java.io.File %) ".edn"))))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} derive-summary
  "Pure: first-event + tail-events + file metadata → summary map or nil."
  [first-event tail-events file-path file-size]
  (let [wf-id (extract-wf-id first-event)]
    (when wf-id
      (let [terminal    (find-by-type #{:workflow/completed :workflow/failed} tail-events)
            phase-event (find-by-type #{:workflow/phase-started :workflow/phase-completed} tail-events)]
        {:id           wf-id
         :name         (extract-wf-name first-event wf-id)
         :status       (derive-status terminal (count tail-events))
         :phase        (or (:workflow/phase phase-event) (:phase phase-event)
                           (:workflow/phase first-event) (:phase first-event)
                           "unknown")
         :started-at   (extract-timestamp first-event)
         :completed-at (when terminal (extract-timestamp terminal))
         :file-path    file-path
         :file-size    file-size}))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-workflow-summary
  "Read a lightweight summary from an .edn event file.
   Opens file, reads first event + tail events, derives summary.
   Returns summary map or nil on error."
  [^java.io.File file]
  (try
    (let [raf (RandomAccessFile. file "r")]
      (try
        (let [first-event (read-first-event raf)
              tail-events (read-tail-events raf (.length file))]
          (derive-summary first-event tail-events (.getPath file) (.length file)))
        (finally
          (.close raf))))
    (catch Exception _ nil)))

(defn ^{:stratum 2} read-dir-summary
  "Summary of `workflow-id`'s file sink directory under `events-dir`,
   from its first-millisecond files (plus two) and its last
   `tail-file-count`. Keeps `events-dir`, not the directory, which an
   archive may move. nil when it holds no readable event."
  [events-dir workflow-id]
  (when-let [dir (es/workflow-events-dir events-dir workflow-id)]
    (let [files      (event-files/event-files dir)
          first-ms   (some-> (first files) event-files/file-ms)
          head-count (+ 2 (count (take-while #(= first-ms (event-files/file-ms %)) files)))]
      (some-> (derive-summary (first (event-files/read-ordered (take head-count files)))
                              (->> (first (event-files/newest-batches files tail-file-count))
                                   (take-last tail-file-count)
                                   reverse
                                   vec)
                              nil
                              (reduce + 0 (map #(.length ^File %) files)))
              (assoc :events-dir (str events-dir))))))

;------------------------------------------------------------------------------ Layer 3

(defn ^{:stratum 3} scan-archive!
  "Scan the legacy .edn files and every workflow directory in events-dir
   and build lightweight summaries. Calls (on-workflow! summary) for each
   successfully parsed workflow (one in both layouts: twice, its
   directory last), then (on-complete!). Returns nil."
  [events-dir on-workflow! on-complete!]
  (try
    (doseq [file (list-edn-files events-dir)]
      (when-let [summary (read-workflow-summary file)]
        (on-workflow! summary)))
    (doseq [workflow-id (es/stored-workflow-ids events-dir)
            :when (not (str/starts-with? workflow-id "."))]
      (when-let [summary (read-dir-summary events-dir workflow-id)]
        (on-workflow! summary)))
    (catch Exception _ nil))
  (on-complete!)
  nil)
