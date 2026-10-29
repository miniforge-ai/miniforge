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
(ns ai.miniforge.web-dashboard.watcher
  "File-based event watcher for the dashboard.

   The CLI's event-stream file sink writes one transit-JSON file per event
   under `{events-dir}/live/<workflow-id>/`; a JVM run's archive moves the
   directory to `{events-dir}/archived/<workflow-id>/`. The watcher polls
   those directories and publishes each new event, decoded by the
   event-stream reader, to the dashboard's in-memory event stream.

   It polls rather than registering a watch per directory: it runs under
   babashka, and an events dir holds a directory for every run. A poll
   reads only the active directories (modified within the stale
   threshold). The active set is recomputed when a root's mtime changes
   (a workflow directory created or moved), at most every
   `relist-min-ms`, and every `sweep-ms`, which also picks up an old
   directory written to again.

   Directories already active at startup are read in full, so a run in
   progress arrives with its start event. In other directories, files
   modified before startup are skipped; the archive scan and
   `historical-events` read those."
  (:require
   [ai.miniforge.web-dashboard.config :as dashboard-config]
   [ai.miniforge.web-dashboard.event-files :as event-files]
   [clojure.edn :as edn]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} parse-edn-line
  "Parse a single EDN line, returning nil on parse failure."
  [line]
  (try
    (when (and line (not= "" (.trim ^String line)))
      (edn/read-string line))
    (catch Exception _
      nil)))

(def ^{:stratum 0} poll-ms
  "Interval between polls."
  500)

(def ^{:stratum 0} relist-min-ms
  "Least time between two relists triggered by a root change."
  1000)

(def ^{:stratum 0} sweep-ms
  "Longest time between two relists."
  10000)

(def ^{:stratum 0} active-window-ms
  "A workflow directory modified within this window is active."
  (:stale-threshold-ms dashboard-config/defaults))

(def ^{:stratum 0} mtime-slack-ms
  "Some file systems keep mtimes to the second, so a file written just
   after startup can carry an earlier mtime. Files this close before
   startup count as new; a repeat is deduped by event id."
  1000)

(defn ^{:stratum 0} relist
  "`state` with its active directories recomputed at `now`. A directory
   no longer active has had nothing written for `window-ms`, and was
   listed after its last write, so its seen names are replaced by the
   time `now - window-ms`: should it become active again, the files
   modified from then on are the new ones."
  [state events-dir roots window-ms now]
  (let [active  (event-files/active-dirs events-dir (- now window-ms))
        dropped (remove (set (keys active)) (keys (:seen state)))]
    (assoc state
           :active active
           :roots roots
           :listed-at now
           :seen (select-keys (:seen state) (keys active))
           :since (into (get state :since {})
                        (map #(vector % (- now window-ms)))
                        dropped))))

(defn ^{:stratum 0} tail-dir
  "Publish the unseen events of directory `dir`, named `wf-name`, in
   creation order. Returns `state` with the directory's seen names. A
   failing publish skips that event only."
  [publish-fn state wf-name dir]
  (let [[names files] (event-files/unseen-files
                       dir
                       (get-in state [:seen wf-name])
                       (get-in state [:since wf-name] (:since-ms state)))]
    (doseq [event (event-files/read-ordered files)]
      (try+
        (publish-fn event)
        (catch Exception _ nil)))
    (assoc-in state [:seen wf-name] names)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} initial-state
  "Watcher state at `now`: the directories active now are read in full on
   the first poll; elsewhere, files modified before `now` (less
   `mtime-slack-ms`) are skipped. Roots are read before the directories,
   so a directory created in between still changes them."
  [events-dir now]
  (let [roots (event-files/root-mtimes events-dir)
        state (relist {:since-ms (- now mtime-slack-ms) :seen {}}
                      events-dir roots active-window-ms now)]
    (assoc state :seen (zipmap (keys (:active state)) (repeat #{})))))

(defn ^{:stratum 1} poll-once!
  "One poll at `now`: relist when a sweep is due or a root may have
   changed since the last listing, then publish the new events of every
   active directory. Returns the new state. A root whose mtime falls
   within `mtime-slack-ms` of the last listing counts as changed: a
   second change in the same mtime tick would not show."
  [state events-dir publish-fn now]
  (let [{:keys [listed-at]} state
        roots   (event-files/root-mtimes events-dir)
        elapsed (- now listed-at)
        changed? (or (not= roots (:roots state))
                     (some #(>= % (- listed-at mtime-slack-ms)) roots))
        relist? (or (>= elapsed sweep-ms)
                    (and changed? (>= elapsed relist-min-ms)))
        state   (if relist?
                  (relist state events-dir roots active-window-ms now)
                  state)]
    (reduce-kv (partial tail-dir publish-fn) state (:active state))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start-watcher!
  "Start a daemon thread that polls `events-dir` every `poll-ms` and calls
   `publish-fn` with each new event (see the namespace doc).

   Arguments:
     events-dir  - The events root, e.g. ~/.miniforge/events
     publish-fn  - Function (fn [event-map] ...) called for each new event

   Returns: cleanup function that stops the watcher."
  [events-dir publish-fn]
  (let [running (atom true)
        thread (Thread.
                (fn []
                  (let [state (atom nil)]
                    (while @running
                      (try+
                        (let [now (System/currentTimeMillis)]
                          (reset! state (if-let [s @state]
                                          (poll-once! s events-dir publish-fn now)
                                          (initial-state events-dir now))))
                        (Thread/sleep (long poll-ms))
                        (catch InterruptedException _
                          (reset! running false))
                        (catch Exception _
                          ;; Transient I/O error — retry on next poll
                          (try+ (Thread/sleep 1000)
                                (catch InterruptedException _
                                  (reset! running false)))))))))]
    (.setDaemon thread true)
    (.setName thread "miniforge-event-watcher")
    (.start thread)
    (fn []
      (reset! running false)
      (.interrupt thread))))
