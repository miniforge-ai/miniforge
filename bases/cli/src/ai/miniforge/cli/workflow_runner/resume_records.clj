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
   a run's origin (`origin.edn` beside its events), the latest launch per
   workflow (`<events>/operator/.resume-launches/<workflow>.edn`: the
   intervention, run id, pid and pid start instant), and start evidence
   (an event under the run id carrying the intervention id as its
   `:workflow-run/correlation-id`)."
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

(defn- ^{:stratum 0} launch-file
  ^java.io.File [workflow-id]
  (io/file (es/operator-dir (es/default-events-dir)) ".resume-launches"
           (str workflow-id ".edn")))

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

(defn ^{:stratum 0} plan-run-id
  "The plan's snapshot id (what `mf resume` restores), else a fresh id."
  [plan]
  (or (get-in plan [:resume/machine-snapshot :execution/id]) (random-uuid)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} record-origin!
  "Record the directory this process runs `workflow-id` from. Best
   effort: when it cannot be written, a retry is refused instead."
  [workflow-id]
  (try (write-edn! (io/file (run-dir workflow-id) "origin.edn")
                   {:cwd (System/getProperty "user.dir")})
       (catch java.io.IOException _ nil)))

(defn ^{:stratum 1} recorded-origin
  "The recorded origin of `workflow-id` when it still exists, else nil."
  [workflow-id]
  (let [cwd (:cwd (read-edn (io/file (run-dir workflow-id) "origin.edn")))]
    (when (and cwd (.isDirectory (io/file cwd))) cwd)))

(defn ^{:stratum 1} launch-record [workflow-id] (read-edn (launch-file workflow-id)))

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
                 (filter (partial recent-event-file? since-ms) (.listFiles (run-dir run-id))))))

(defn ^{:stratum 1} failure
  "An anomaly naming the lifecycle failure `code` and its `details`."
  [anomaly-type code details]
  (anomaly/anomaly anomaly-type
                   (system-message :anomaly/resume-refused {:code (name code)})
                   (assoc details :failure/code code)))

(defn ^{:stratum 1} start!
  "Record the launch, spawn it with `(spawn! run-id log-file)` → pid, and
   record the pid. Recorded before the spawn too: a crash between the two
   must not let a redelivery spawn a second child."
  [plan spawn!]
  (let [workflow-id (:resume/workflow-id plan)
        run-id (plan-run-id plan)
        intervention-id (str (:resume/intervention-id plan))
        log-file (str (io/file (app-config/logs-dir) (str "resume-" run-id ".log")))
        launched-at-ms (System/currentTimeMillis)
        launch {:resume/intervention-id intervention-id
                :resume/run-id run-id
                :resume/log log-file
                :resume/launched-at-ms launched-at-ms}
        _ (write-edn! (launch-file workflow-id) launch)
        pid (spawn! run-id log-file)
        started (start-instant (process-handle pid))
        recorded (assoc launch :resume/pid pid :resume/pid-started started)]
    (write-edn! (launch-file workflow-id) recorded)
    recorded))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} launch-running?
  [record]
  (boolean (and record (process-running? (:resume/pid record) (:resume/pid-started record)))))

(defn ^{:stratum 2} target-live?
  "A live runner in this process, or a live manifest owner (JVM runners
   only: a Babashka runner in another process is not seen)."
  [workflow-id]
  (or (operator/live-runner? workflow-id) (manifest-owner-alive? workflow-id)))
