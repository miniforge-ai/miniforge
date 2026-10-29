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
   each new event to a callback. It polls the directories modified within
   `:window-ms`, relisting them on a root change and every `sweep-ms`.
   Runs active at startup are read in full; elsewhere, files recorded
   before startup are skipped. Per run, see `layout/track` and
   `layout/read-unread`; a directory leaving the active set is read each
   poll until nothing is pending, then keeps only its watermark."
  (:require
   [ai.miniforge.event-stream.layout-reader :as layout]
   [ai.miniforge.logging.interface :as log]
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
   leaving the set go under :leaving. A root that cannot be listed leaves
   `state` as it was, so the next poll lists again."
  [{:keys [window-ms active] :as state} events-dir roots now]
  (if-let [listed (layout/active-dirs events-dir (- now window-ms))]
    (assoc state
           :active listed
           :leaving (apply dissoc active (keys listed))
           :roots roots
           :listed-at now)
    state))

(defn ^{:stratum 0} tail-dir
  "Hand the unread events of workflow `wf-name` (directory `dir`, or its
   archived/ directory once the run has moved) to `on-event` in creation
   order, and track them. A file given up as corrupt is logged. A failed
   listing is retried, unless the run's directory is gone from live/ and
   archived/ alike. A failing `on-event` skips that event only."
  [on-event events-dir state wf-name dir]
  (let [tracked (get-in state [:tracked wf-name] {:watermark (:since-ms state) :names {}})
        files   (or (layout/unread-files (layout/current-dir events-dir wf-name dir) tracked)
                    (when-not (.exists (layout/current-dir events-dir wf-name dir)) []))
        [events tracked gaps] (layout/read-unread files tracked)
        ;; Anything but an Error, an ex-info included (try+ hands one
        ;; over as its data, which an Exception selector misses).
        nonfatal? (fn [thrown] (not (instance? Error thrown)))]
    (doseq [f gaps]
      ;; Best effort: a logger that fails must not hold back delivery.
      (try+ (log/warn (:logger state) :event-stream :event-tail/gap
                      {:message "Event file given up after repeated failed reads"
                       :data    {:workflow wf-name :file (str f)}})
            (catch nonfatal? _ nil)))
    (doseq [event events]
      (try+
        (on-event event)
        (catch nonfatal? _ nil)))
    (assoc-in state [:tracked wf-name] tracked)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} initial-state
  "Tail state at `now`: the directories active now are read in full on
   the first poll; elsewhere, files recorded before `now` (less
   `mtime-slack-ms`) are skipped. Roots are read before the directories,
   so a directory created in between still changes them. Nil when a root
   cannot be listed: a startup without the runs active then is retried."
  [events-dir now window-ms]
  (let [roots  (layout/root-mtimes events-dir)
        active (layout/active-dirs events-dir (- now window-ms))]
    (when active
      {:since-ms  (- now mtime-slack-ms)
       :window-ms window-ms
       :roots     roots
       :listed-at now
       :active    active
       :tracked   (zipmap (keys active) (repeat {:watermark 0 :names {}}))})))

(defn ^{:stratum 1} poll-once!
  "One poll at `now`: relist when a sweep is due or a root may have
   changed since the last listing, then hand on the new events of every
   active directory, and of each one no longer active whose last read
   left something pending (:retrying). Once a read leaves nothing
   pending, such a directory keeps only its watermark. Returns the new
   state."
  [state events-dir on-event now]
  (let [{:keys [listed-at]} state
        roots    (layout/root-mtimes events-dir)
        elapsed  (- now listed-at)
        changed? (or (not= roots (:roots state))
                     (some #(>= % (- listed-at mtime-slack-ms)) roots))
        relist?  (or (>= elapsed sweep-ms)
                     (and changed? (>= elapsed relist-min-ms)))
        state    (if relist? (relist state events-dir roots now) state)
        active   (:active state)
        draining (apply dissoc (merge (:retrying state) (:leaving state)) (keys active))
        state    (reduce-kv (partial tail-dir on-event events-dir)
                            (dissoc state :leaving)
                            (merge draining active))
        settled  (remove #(get-in state [:tracked % :pending?]) (keys draining))]
    (-> (reduce #(update-in %1 [:tracked %2] layout/retire) state settled)
        (assoc :retrying (apply dissoc draining settled)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} start!
  "Start a daemon thread that polls `events-dir` every `:poll-ms` and
   calls `on-event` with each new event (see the namespace doc). Options
   (`defaults`): :poll-ms, :window-ms, :thread-name, and :logger for
   gaps (default: :warn and above, human-readable). The first listing
   runs on that thread. Returns a function that stops it."
  [events-dir on-event & [opts]]
  (let [{:keys [poll-ms window-ms thread-name]} (merge defaults opts)
        logger  (get opts :logger (log/create-logger {:min-level :warn :output :human}))
        running (atom true)
        nonfatal? (fn [thrown] (not (instance? Error thrown)))
        thread  (Thread.
                 (fn []
                   (let [state (atom nil)]
                     (while @running
                       (try+
                         (let [now (System/currentTimeMillis)]
                           (reset! state (if-let [s @state]
                                           (poll-once! s events-dir on-event now)
                                           (some-> (initial-state events-dir now window-ms)
                                                  (assoc :logger logger)))))
                         (Thread/sleep (long poll-ms))
                         (catch InterruptedException _
                           (reset! running false))
                         (catch nonfatal? _
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
