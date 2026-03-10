(ns ai.miniforge.web-dashboard.archive
  "Archive scanning — build lightweight workflow summaries from event files.

   Reads only first+last lines per file (two seeks) to extract name, status,
   and timestamps without loading all events into memory."
  (:require
   [clojure.java.io :as io]
   [ai.miniforge.web-dashboard.watcher :as watcher])
  (:import
   [java.io RandomAccessFile]))

;------------------------------------------------------------------------------ Layer 0
;; Pure summary derivation — data in, data out

(defn extract-wf-id
  [event]
  (or (:workflow/id event) (:workflow-id event)))

(defn extract-wf-name
  [event wf-id]
  (or (get-in event [:workflow/spec :spec/title])
      (get-in event [:workflow/spec :title])
      (get-in event [:workflow/spec :name])
      (get-in event [:workflow-spec :title])
      (get-in event [:workflow-spec :name])
      (get-in event [:spec :title])
      (get-in event [:spec :name])
      (str "Workflow " (subs (str wf-id) 0 (min 8 (count (str wf-id)))))))

(defn extract-timestamp
  [event]
  (or (:event/timestamp event) (:timestamp event)))

(defn find-by-type
  "First event from coll whose :event/type is in type-set."
  [type-set events]
  (first (filter #(contains? type-set (:event/type %)) events)))

(defn derive-status
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

(defn derive-summary
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

;------------------------------------------------------------------------------ Layer 1
;; I/O: read first+last lines from event file

(defn read-first-event
  "Read and parse the first line of a RandomAccessFile."
  [^RandomAccessFile raf]
  (-> (.readLine raf) watcher/parse-edn-line))

(defn read-tail-events
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

(defn read-workflow-summary
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

;------------------------------------------------------------------------------ Layer 2
;; Archive scan orchestration

(defn list-edn-files
  [events-dir]
  (let [dir (io/file events-dir)]
    (when (and (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName ^java.io.File %) ".edn"))))))

(defn scan-archive!
  "Scan all .edn files in events-dir, build lightweight summaries.
   Calls (on-workflow! summary) for each successfully parsed file.
   Calls (on-complete!) when scan finishes. Returns nil."
  [events-dir on-workflow! on-complete!]
  (try
    (doseq [file (list-edn-files events-dir)]
      (when-let [summary (read-workflow-summary file)]
        (on-workflow! summary)))
    (catch Exception _ nil))
  (on-complete!)
  nil)
