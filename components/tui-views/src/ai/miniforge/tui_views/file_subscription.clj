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

(ns ai.miniforge.tui-views.file-subscription
  "File-based event subscription for standalone TUI mode.

   Tail-follows ~/.miniforge/events/*.edn files and dispatches parsed
   events to the TUI dispatch-fn. Enables the TUI to monitor running
   workflows without an in-memory event stream — cross-process via
   the filesystem protocol used by event-stream file-sink."
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [ai.miniforge.tui-views.subscription :as subscription]))

;------------------------------------------------------------------------------ Layer 0
;; Directory and file helpers

(defn- events-dir
  "Returns ~/.miniforge/events/ as a java.io.File."
  []
  (io/file (System/getProperty "user.home") ".miniforge" "events"))

(defn- scan-event-files
  "Find *.edn files in dir, returns sorted list of java.io.File."
  [^java.io.File dir]
  (if (and dir (.exists dir) (.isDirectory dir))
    (->> (.listFiles dir)
         (filter #(.endsWith (.getName ^java.io.File %) ".edn"))
         (sort-by #(.getName ^java.io.File %))
         vec)
    []))

(defn- read-new-lines
  "Read lines added since last position using RandomAccessFile.
   Updates position-atom with new file length. Returns vector of line strings."
  [^java.io.File file position-atom]
  (try
    (let [current-length (.length file)]
      (if (<= current-length @position-atom)
        []
        (with-open [raf (java.io.RandomAccessFile. file "r")]
          (.seek raf @position-atom)
          (let [lines (loop [acc []]
                        (if-let [line (.readLine raf)]
                          (recur (conj acc line))
                          acc))]
            (reset! position-atom (.getFilePointer raf))
            lines))))
    (catch Exception _
      [])))

;------------------------------------------------------------------------------ Layer 1
;; Parse and dispatch

(defn- parse-and-dispatch!
  "Parse each line as EDN, translate via subscription/translate-event,
   dispatch non-nil results to dispatch-fn."
  [lines dispatch-fn]
  (doseq [line lines]
    (when (seq line)
      (try
        (let [event (edn/read-string line)]
          (when-let [msg (subscription/translate-event event)]
            (dispatch-fn msg)))
        (catch Exception _
          ;; Malformed EDN line — skip silently
          nil)))))

;------------------------------------------------------------------------------ Layer 2
;; Command sending

(defn send-command!
  "Send a command to a running workflow via filesystem protocol.
   Writes a .edn command file that the workflow runner's command poller picks up."
  [workflow-id command]
  (let [commands-dir (io/file (System/getProperty "user.home")
                              ".miniforge" "commands" (str workflow-id))
        cmd-file (io/file commands-dir (str (System/currentTimeMillis) ".edn"))]
    (.mkdirs commands-dir)
    (spit cmd-file (pr-str {:command command :timestamp (java.util.Date.)}))))

;------------------------------------------------------------------------------ Layer 3
;; File tracking and polling

(defn- track-file!
  "Track a new event file: register it in tracked map and hydrate
   all existing lines through dispatch-fn."
  [tracked dispatch-fn ^java.io.File f]
  (let [path (.getAbsolutePath f)
        pos  (atom 0)]
    (swap! tracked assoc path {:file f :position pos})
    (let [lines (read-new-lines f pos)]
      (parse-and-dispatch! lines dispatch-fn))))

(defn- poll-tracked-files!
  "Read new lines from all tracked files and dispatch them."
  [tracked dispatch-fn]
  (doseq [[_path {:keys [file position]}] @tracked]
    (let [lines (read-new-lines file position)]
      (when (seq lines)
        (parse-and-dispatch! lines dispatch-fn)))))

(defn- scan-for-new-files!
  "Check for new .edn files in dir and start tracking them."
  [dir tracked dispatch-fn]
  (let [current-files (scan-event-files dir)
        tracked-paths (set (keys @tracked))]
    (doseq [^java.io.File f current-files]
      (when-not (tracked-paths (.getAbsolutePath f))
        (track-file! tracked dispatch-fn f)))))

(defn- poll-loop
  "Polling loop body for the file-subscription daemon thread.
   Polls tracked files every poll-ms, scans for new files every scan-ms."
  [running? tracked dispatch-fn dir poll-ms scan-ms]
  (try
    (let [scan-counter (atom 0)]
      (while @running?
        (Thread/sleep poll-ms)
        (when @running?
          (poll-tracked-files! tracked dispatch-fn)
          (swap! scan-counter + poll-ms)
          (when (>= @scan-counter scan-ms)
            (reset! scan-counter 0)
            (scan-for-new-files! dir tracked dispatch-fn)))))
    (catch InterruptedException _)
    (catch Exception _)))

(defn- stop-subscription!
  "Stop the file-subscription polling thread."
  [running? ^Thread thread]
  (reset! running? false)
  (.interrupt thread))

;------------------------------------------------------------------------------ Layer 4
;; Main subscription entry point

(defn subscribe-to-files!
  "Subscribe to workflow event files via tail-following.

   Scans ~/.miniforge/events/ for existing .edn files, hydrates initial
   state from all existing lines, then polls for new lines and new files
   on a background daemon thread.

   Arguments:
   - dispatch-fn - (fn [msg]) to send TUI messages into the Elm loop
   - opts        - {:poll-ms 500, :scan-ms 2000} polling intervals

   Returns: cleanup function (fn [] ...) to call on shutdown."
  [dispatch-fn & [opts]]
  (let [poll-ms   (get opts :poll-ms 500)
        scan-ms   (get opts :scan-ms 2000)
        dir       (events-dir)
        tracked   (atom {})
        running?  (atom true)
        _         (doseq [f (scan-event-files dir)]
                    (track-file! tracked dispatch-fn f))
        thread    (doto (Thread. #(poll-loop running? tracked dispatch-fn dir poll-ms scan-ms))
                    (.setDaemon true)
                    (.setName "tui-file-subscription")
                    (.start))]
    (partial stop-subscription! running? thread)))

;------------------------------------------------------------------------------ Rich Comment
(comment
  ;; Test file subscription
  (def messages (atom []))
  (def cleanup (subscribe-to-files! (fn [msg] (swap! messages conj msg))))

  @messages

  ;; Send a test command
  (send-command! "test-workflow-id" :pause)

  (cleanup)

  :leave-this-here)
