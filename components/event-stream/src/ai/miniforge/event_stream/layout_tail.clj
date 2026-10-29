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
(ns ai.miniforge.event-stream.layout-tail
  "Follow the runs other processes write through the file sink, and hand
   each new event to a callback.

   It polls rather than registering a watch per directory: it runs under
   babashka, and an events dir holds a directory for every run. A poll
   reads only the active directories (modified within `:window-ms`). The
   active set is recomputed when a root's mtime changes or sits within a
   second of the last listing (a workflow directory created or moved), at
   most every `relist-min-ms`, and every `sweep-ms`, which also picks up
   an old directory written to again.

   Each run is tracked by a watermark and the names read above it
   (`layout/track`). Only a decoded file counts as read: one that failed
   to decode, or vanished in a move to archived/, is read on a later
   poll, from archived/ under the same name. A directory leaving the
   active set is read once more first, so a pause in polling loses
   nothing; then only its watermark is kept.

   Directories already active at startup are read in full, so a run in
   progress arrives with its start event. In other directories, files
   recorded before startup are skipped."
  (:require
   [ai.miniforge.event-stream.layout-reader :as layout]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} defaults
  "Poll interval, and the window within which a directory counts as
   active (the dashboard's stale threshold)."
  {:poll-ms 500 :window-ms 600000 :thread-name "miniforge-event-tail"})

(def ^{:stratum 0} relist-min-ms
  "Least time between two relists triggered by a root change."
  1000)

(def ^{:stratum 0} sweep-ms
  "Longest time between two relists."
  10000)

(def ^{:stratum 0} mtime-slack-ms
  "Some file systems keep mtimes to the second, and some file names
   record only the second, so a file written just after startup can
   carry an earlier time, and two changes to a root in one tick look
   like one. Times this close count as new."
  1000)

(defn ^{:stratum 0} relist
  "`state` with its active directories recomputed at `now`; those
   leaving the set go under :leaving."
  [{:keys [window-ms active] :as state} events-dir roots now]
  (let [listed (layout/active-dirs events-dir (- now window-ms))]
    (assoc state
           :active listed
           :leaving (apply dissoc active (keys listed))
           :roots roots
           :listed-at now)))

(defn ^{:stratum 0} tail-dir
  "Hand the unread events of workflow `wf-name` (directory `dir`, or its
   archived/ directory once the run has moved) to `on-event` in creation
   order, and track the files decoded. A failing `on-event` skips that
   event only."
  [on-event events-dir state wf-name dir]
  (let [tracked (get-in state [:tracked wf-name] {:watermark (:since-ms state) :names {}})
        events  (layout/read-ordered
                 (layout/unread-files (layout/current-dir events-dir wf-name dir) tracked))]
    (doseq [event events]
      (try+
        (on-event event)
        (catch Exception _ nil)))
    (assoc-in state [:tracked wf-name] (layout/track tracked (map layout/source-file events)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} initial-state
  "Tail state at `now`: the directories active now are read in full on
   the first poll; elsewhere, files recorded before `now` (less
   `mtime-slack-ms`) are skipped. Roots are read before the directories,
   so a directory created in between still changes them."
  [events-dir now window-ms]
  (let [roots  (layout/root-mtimes events-dir)
        active (layout/active-dirs events-dir (- now window-ms))]
    {:since-ms  (- now mtime-slack-ms)
     :window-ms window-ms
     :roots     roots
     :listed-at now
     :active    active
     :tracked   (zipmap (keys active) (repeat {:watermark 0 :names {}}))}))

(defn ^{:stratum 1} poll-once!
  "One poll at `now`: relist when a sweep is due or a root may have
   changed since the last listing, then hand on the new events of every
   active directory and of each one leaving the set, which then keeps
   only its watermark. Returns the new state."
  [state events-dir on-event now]
  (let [{:keys [listed-at]} state
        roots    (layout/root-mtimes events-dir)
        elapsed  (- now listed-at)
        changed? (or (not= roots (:roots state))
                     (some #(>= % (- listed-at mtime-slack-ms)) roots))
        relist?  (or (>= elapsed sweep-ms)
                     (and changed? (>= elapsed relist-min-ms)))
        state    (if relist? (relist state events-dir roots now) state)
        leaving  (:leaving state)
        state    (reduce-kv (partial tail-dir on-event events-dir)
                            (dissoc state :leaving)
                            (merge leaving (:active state)))]
    (reduce #(update-in %1 [:tracked %2] layout/retire) state (keys leaving))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start!
  "Start a daemon thread that polls `events-dir` every `:poll-ms` and
   calls `on-event` with each new event (see the namespace doc). Options
   (`defaults`): :poll-ms, :window-ms, :thread-name. The first listing
   runs on that thread. Returns a function that stops it."
  [events-dir on-event & [opts]]
  (let [{:keys [poll-ms window-ms thread-name]} (merge defaults opts)
        running (atom true)
        thread  (Thread.
                 (fn []
                   (let [state (atom nil)]
                     (while @running
                       (try+
                         (let [now (System/currentTimeMillis)]
                           (reset! state (if-let [s @state]
                                           (poll-once! s events-dir on-event now)
                                           (initial-state events-dir now window-ms))))
                         (Thread/sleep (long poll-ms))
                         (catch InterruptedException _
                           (reset! running false))
                         (catch Exception _
                           ;; Transient I/O error — retry on the next poll
                           (try+ (Thread/sleep 1000)
                                 (catch InterruptedException _
                                   (reset! running false)))))))))]
    (.setDaemon thread true)
    (.setName thread ^String thread-name)
    (.start thread)
    (fn []
      (reset! running false)
      (.interrupt thread))))
