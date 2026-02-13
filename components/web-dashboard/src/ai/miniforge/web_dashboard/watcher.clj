(ns ai.miniforge.web-dashboard.watcher
  "File-based event watcher for the dashboard.

   Tail-follows ~/.miniforge/events/*.edn and publishes parsed events
   to the dashboard's internal event stream. Replaces the HTTP event bridge
   with a zero-network architecture: CLI writes events to files via the
   default file sink, and this watcher reads them."
  (:require
   [clojure.java.io :as io]
   [clojure.edn :as edn])
  (:import
   [java.io RandomAccessFile]))

;------------------------------------------------------------------------------ Layer 0
;; File reading

(defn- read-new-lines
  "Read new lines from file starting at byte offset.
   Returns [new-offset lines] where lines is a vector of strings."
  [^java.io.File file ^long offset]
  (let [length (.length file)]
    (if (<= length offset)
      [offset []]
      (let [raf (RandomAccessFile. file "r")]
        (try
          (.seek raf offset)
          (loop [lines (transient [])]
            (if-let [line (.readLine raf)]
              (recur (conj! lines line))
              [(.getFilePointer raf) (persistent! lines)]))
          (finally
            (.close raf)))))))

(defn parse-edn-line
  "Parse a single EDN line, returning nil on parse failure."
  [line]
  (try
    (when (and line (not= "" (.trim ^String line)))
      (edn/read-string line))
    (catch Exception _
      nil)))

;------------------------------------------------------------------------------ Layer 1
;; Polling

(defn- poll-once!
  "Scan events dir, read new EDN lines from each .edn file,
   call publish-fn for each parsed event. Returns updated offsets map."
  [events-dir offsets publish-fn]
  (let [dir (io/file events-dir)]
    (if (and (.exists dir) (.isDirectory dir))
      (let [edn-files (->> (.listFiles dir)
                           (filter #(.endsWith (.getName ^java.io.File %) ".edn")))]
        (reduce
         (fn [acc ^java.io.File file]
           (let [path (.getPath file)
                 current-offset (get acc path 0)
                 [new-offset lines] (read-new-lines file current-offset)]
             (doseq [line lines]
               (when-let [event (parse-edn-line line)]
                 (try
                   (publish-fn event)
                   (catch Exception _ nil))))
             (assoc acc path new-offset)))
         offsets
         edn-files))
      offsets)))

;------------------------------------------------------------------------------ Layer 2
;; Watcher lifecycle

(defn- initialize-offsets
  "Scan existing .edn files and set offsets to end-of-file.
   This skips historical data so the watcher only sees new events."
  [events-dir]
  (let [dir (io/file events-dir)]
    (if (and (.exists dir) (.isDirectory dir))
      (->> (.listFiles dir)
           (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
           (reduce (fn [acc ^java.io.File file]
                     (assoc acc (.getPath file) (.length file)))
                   {}))
      {})))

(defn start-watcher!
  "Start a daemon thread that polls events dir every 500ms.
   Publishes parsed events via publish-fn.

   On startup, seeks to end of all existing files (skips history).
   Only new bytes appended after startup and new files are published.

   Arguments:
     events-dir  - Path to ~/.miniforge/events directory
     publish-fn  - Function (fn [event-map] ...) called for each new event

   Returns: cleanup function that stops the watcher."
  [events-dir publish-fn]
  (let [running (atom true)
        offsets (atom (initialize-offsets events-dir))
        thread (Thread.
                (fn []
                  (while @running
                    (try
                      (swap! offsets poll-once! events-dir publish-fn)
                      (Thread/sleep 500)
                      (catch InterruptedException _
                        (reset! running false))
                      (catch Exception _
                        ;; Transient I/O error — retry on next poll
                        (try (Thread/sleep 1000) (catch InterruptedException _ nil)))))))]
    (.setDaemon thread true)
    (.setName thread "miniforge-event-watcher")
    (.start thread)
    (fn []
      (reset! running false)
      (.interrupt thread))))
