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
(ns ai.miniforge.cli.workflow-runner.resume-records
  "What the resume launcher keeps on disk so a retry starts at most once
   per intervention, never over a live run, and only where the run began:
   a run's origin (`origin.edn` beside its events, moving with them when
   the run is archived: the directory and the runner's pid and start
   instant), the latest launch per workflow
   (`<events>/operator/.resume-launches/<workflow>.edn`: the intervention,
   run id, pid and pid start instant), the pid file the child writes
   itself (`<home>/logs/resume-<run-id>.pid`, named in the launch before
   the spawn), and start evidence (an event under the run id carrying the
   intervention id as its `:workflow-run/correlation-id`).

   Retries form a lineage: the run first retried (its root) and every
   attempt a retry started, each named in
   `.resume-launches/lineage/<attempt>.edn`. The latest launch is recorded
   once per lineage, under its root, with the lineage's attempts."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.messages.interface :as messages]
   [ai.miniforge.operator.interface :as operator]
   [cheshire.core :as json]
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   [java.nio.file Files StandardCopyOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private system-message
  (messages/create-translator "config/cli/messages/system.edn" :cli/system))

(defn ^{:stratum 0} run-dir
  ^java.io.File [id]
  (es/workflow-dir (es/default-events-dir) (str id)))

(defn- ^{:stratum 0} recorded-run-dir
  "Where the run's events are now — archived, live, or legacy — or nil."
  ^java.io.File [id]
  (es/workflow-events-dir (es/default-events-dir) (str id)))

(defn- ^{:stratum 0} launch-file
  "The launch records' directory, or the record of `workflow-id`."
  (^java.io.File []
   (io/file (es/operator-dir (es/default-events-dir)) ".resume-launches"))
  (^java.io.File [workflow-id]
   (io/file (launch-file) (str workflow-id ".edn"))))

(defn- ^{:stratum 0} lineage-file
  "Where an attempt a retry started names its lineage's root."
  ^java.io.File [run-id]
  (io/file (es/operator-dir (es/default-events-dir)) ".resume-launches" "lineage"
           (str run-id ".edn")))

(defn- ^{:stratum 0} read-edn
  [^java.io.File f]
  (when (.exists f)
    (try (edn/read-string (slurp f)) (catch Exception _ nil))))

(defn- ^{:stratum 0} write-edn!
  "Write `data` whole: a temp file, then an atomic rename."
  [^java.io.File f data]
  (let [tmp (io/file (.getParentFile f) (str (.getName f) ".tmp"))]
    (io/make-parents f)
    (spit tmp (pr-str data))
    (Files/move (.toPath tmp) (.toPath f)
                (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                      StandardCopyOption/REPLACE_EXISTING]))))

(defn- ^{:stratum 0} read-json
  [^java.io.File f]
  (try (json/parse-string (slurp f) true) (catch Exception _ nil)))

(defn- ^{:stratum 0} recent-event-file?
  "An event file modified since `since-ms` (with slack for coarse clocks)."
  [since-ms ^java.io.File f]
  (and (str/ends-with? (.getName f) ".json")
       (>= (.lastModified f) (- since-ms 2000))))

(defn ^{:stratum 0} process-handle
  [pid]
  (when pid (.orElse (java.lang.ProcessHandle/of (long pid)) nil)))

(defn- ^{:stratum 0} start-instant
  "When the process started: a recycled pid is not the recorded process."
  [^java.lang.ProcessHandle handle]
  (some-> handle .info .startInstant (.orElse nil) str))

(defn- ^{:stratum 0} read-pid
  "The pid in `f`, or nil while it is missing or still being written."
  [^java.io.File f]
  (try (some-> (slurp f) str/trim parse-long) (catch java.io.IOException _ nil)))

(defn- ^{:stratum 0} pid-file-child
  "The live process `pid` names, when it started between `launched-at-ms`
   and the writing of `f`, as the child that wrote it did. A process that
   took the pid later, or one older than the launch, is not that child."
  ^java.lang.ProcessHandle [pid ^java.io.File f launched-at-ms]
  (let [handle (.orElse (java.lang.ProcessHandle/of (long pid)) nil)
        started (some-> handle .info .startInstant (.orElse nil) .toEpochMilli)]
    (when (and handle (.isAlive handle) started
               (<= (- launched-at-ms 2000) started (+ (.lastModified f) 2000)))
      handle)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record-origin!
  "Record the directory this process runs `workflow-id` from, and this
   process as its runner. Best effort: unrecorded, a retry is refused."
  [workflow-id]
  (let [self (java.lang.ProcessHandle/current)
        origin {:cwd (System/getProperty "user.dir")
                :pid (.pid self)
                :pid-started (start-instant self)}]
    (try (write-edn! (io/file (run-dir workflow-id) "origin.edn") origin)
         (catch java.io.IOException _ nil))))

(defn ^{:stratum 1} release-origin!
  "Drop the runner's pid from the origin once it lets go of the run, so a
   process that runs on is not taken for this run's live runner."
  [workflow-id]
  (when-let [f (some-> (recorded-run-dir workflow-id) (io/file "origin.edn"))]
    (when-let [origin (read-edn f)]
      (try (write-edn! f (dissoc origin :pid :pid-started))
           (catch java.io.IOException _ nil)))))

(defn ^{:stratum 1} origin-record
  [workflow-id]
  (some-> (recorded-run-dir workflow-id) (io/file "origin.edn") read-edn))

(defn ^{:stratum 1} launch-record [workflow-id] (read-edn (launch-file workflow-id)))

(defn ^{:stratum 1} pending-launches
  "Launch records whose verification a stopped process never finished."
  []
  (->> (.listFiles (launch-file))
       (filter #(str/ends-with? (.getName ^java.io.File %) ".edn"))
       (keep read-edn)
       (filter #(and (:resume/intervention %) (not (contains? % :resume/settled))))))

(defn ^{:stratum 1} lineage-root
  "The run `workflow-id`'s lineage descends from: the root recorded for an
   attempt a retry started, else `workflow-id` itself."
  [workflow-id]
  (or (:resume/root (read-edn (lineage-file workflow-id))) (str workflow-id)))

(defn ^{:stratum 1} latest-attempt
  "The newest attempt in `record`'s lineage that has recorded a run (live
   or archived): the only member a retry may start from."
  [record]
  (last (filter recorded-run-dir (:resume/attempts record))))

(defn ^{:stratum 1} process-running?
  "True when `pid` is alive and still the process started at `started`."
  [pid started]
  (let [handle (process-handle pid)]
    (boolean (and handle (.isAlive handle)
                  (or (nil? started) (= started (start-instant handle)))))))

(defn ^{:stratum 1} destroy-process! [pid] (some-> (process-handle pid) .destroy))

(defn ^{:stratum 1} manifest-owner-alive?
  "True when the run's manifest (JVM runners keep one) says it is active
   and its owner pid is alive."
  [workflow-id]
  (let [manifest (read-json (io/file (run-dir workflow-id) "manifest.json"))]
    (boolean (and (= "active" (:status manifest))
                  (some-> (get-in manifest [:owner :pid]) parse-long process-handle .isAlive)))))

(defn ^{:stratum 1} correlated-event?
  "True when an event under `run-id` since `since-ms` is correlated to
   `intervention-id`."
  [run-id intervention-id since-ms]
  (boolean (some #(= (str intervention-id)
                     (str (:workflow-run/correlation-id (es/read-event-file %))))
                 (filter (partial recent-event-file? since-ms)
                         (some-> (recorded-run-dir run-id) .listFiles)))))

(defn ^{:stratum 1} with-child-pid
  "`launch` with its child's pid. The pid recorded after the spawn stands.
   Without one (this process died between the spawn and that record), it
   is the pid the child wrote to `:resume/pid-file`, while that process
   is still the child; a child gone by then marks the launch
   `:resume/exited?`. With no pid written yet, `launch` as it is."
  [{:resume/keys [pid pid-file launched-at-ms] :as launch}]
  (let [f (some-> pid-file io/file)
        child-pid (when (and f (nil? pid)) (read-pid f))
        child (some-> child-pid (pid-file-child f launched-at-ms))]
    (cond
      (nil? child-pid) launch
      child (assoc launch :resume/pid child-pid :resume/pid-started (start-instant child))
      :else (assoc launch :resume/exited? true))))

(defn ^{:stratum 1} failure
  "An anomaly naming the lifecycle failure `code` and its `details`."
  [anomaly-type code details]
  (anomaly/anomaly anomaly-type
                   (system-message :anomaly/resume-refused {:code (name code)})
                   (assoc details :failure/code code)))

(defn ^{:stratum 1} settle!
  "Mark `launch`'s record settled with its intervention's final state
   (unless a later launch has replaced it): a restart leaves it alone."
  [launch final]
  (let [f (launch-file (:resume/workflow-id launch))
        record (read-edn f)]
    (when (= (:resume/intervention-id record) (:resume/intervention-id launch))
      (write-edn! f (assoc record :resume/settled (get final :intervention/state :unrecorded))))))

(defn ^{:stratum 1} start!
  "Record the launch, spawn it with `(spawn! run-id log-file pid-file)` →
   pid, and record the pid. Recorded before the spawn too, naming the pid
   file the child writes: a crash between the two must not let a
   redelivery spawn a second child, nor leave the child untracked."
  [plan spawn!]
  (let [workflow-id (:resume/workflow-id plan)
        root (get plan :resume/root (str workflow-id))
        ;; A new attempt, never the retried run's own id: that run's events
        ;; may be archived, and the attempt's must not land beside them.
        run-id (random-uuid)
        intervention-id (str (:resume/intervention-id plan))
        log-file (str (io/file (app-config/logs-dir) (str "resume-" run-id ".log")))
        pid-file (io/file (app-config/logs-dir) (str "resume-" run-id ".pid"))
        launched-at-ms (System/currentTimeMillis)
        launch {:resume/workflow-id workflow-id
                :resume/intervention-id intervention-id
                :resume/intervention (:resume/intervention plan)
                :resume/from-phase (:resume/from-phase plan)
                :resume/root root
                :resume/retry-of (str workflow-id)
                :resume/attempts (conj (vec (:resume/attempts plan)) (str run-id))
                :resume/run-id run-id
                :resume/log log-file
                :resume/pid-file (str pid-file)
                :resume/launched-at-ms launched-at-ms}
        ;; Only this launch's child may be found by it.
        _ (io/delete-file pid-file true)
        _ (write-edn! (lineage-file run-id) (select-keys launch [:resume/root :resume/retry-of]))
        _ (write-edn! (launch-file root) launch)
        pid (spawn! run-id log-file (str pid-file))
        handle (process-handle pid)
        ;; Read now or never: a child already gone leaves no start
        ;; instant, and its pid may later belong to anything.
        exited? (not (some-> handle .isAlive))
        recorded (cond-> (assoc launch :resume/pid pid :resume/pid-started (start-instant handle))
                   exited? (assoc :resume/exited? true))]
    (write-edn! (launch-file root) recorded)
    recorded))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} recorded-origin
  "The recorded origin directory of `workflow-id` when it still exists,
   else nil."
  [workflow-id]
  (let [cwd (:cwd (origin-record workflow-id))]
    (when (and cwd (.isDirectory (io/file cwd))) cwd)))

(defn ^{:stratum 2} launch-running?
  "True while a launch may still be starting or running: its child (see
   `with-child-pid`) is alive or, with no pid known yet (the child has not
   written its pid file), until `timeout-ms` after the launch. A child
   gone when recorded is not."
  [record timeout-ms]
  (let [{:resume/keys [pid pid-started exited? launched-at-ms]} (some-> record with-child-pid)]
    (boolean
     (and record
          (not exited?)
          (if pid
            (process-running? pid pid-started)
            (< (System/currentTimeMillis) (+ launched-at-ms timeout-ms)))))))

(defn ^{:stratum 2} target-live?
  "The workflow has a live runner: in this process, the runner recorded
   in its origin (any process on this host), or a live manifest owner."
  [workflow-id]
  (let [origin (origin-record workflow-id)]
    (or (operator/live-runner? workflow-id)
        (process-running? (:pid origin) (:pid-started origin))
        (manifest-owner-alive? workflow-id))))
