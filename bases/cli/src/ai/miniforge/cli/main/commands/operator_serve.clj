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
(ns ai.miniforge.cli.main.commands.operator-serve
  "`mf operator serve`: consume `<home>/events/operator/` with no workflow
   run active, so acknowledge / safe-mode / human-review requests,
   intervention decisions, retries, and re-evaluations are acted on
   instead of waiting for the next run. Runs until SIGINT/SIGTERM.

   One server per miniforge home. `<home>/operator-serve.lock` is held
   for the process lifetime; the OS drops it when the process dies,
   however it dies. `<home>/operator-serve.json` (`pid`, `started`,
   `operator-dir`) names the running server and is removed on a clean
   stop; a SIGKILLed server leaves it behind, so readers check the pid
   is alive. The same JSON, plus `\"ready\": true`, is the single line
   printed to stdout once the consumer is polling."
  (:require
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.event-stream.interface :as es]
   [cheshire.core :as json]
   [clojure.java.io :as io])
  (:import
   [java.nio.channels FileChannel]
   [java.nio.file Files OpenOption StandardCopyOption StandardOpenOption]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} lock-file-name "operator-serve.lock")

(def ^{:stratum 0} discovery-file-name "operator-serve.json")

(defn- ^{:stratum 0} overlapping-lock?
  "Another thread of this JVM holds the lock. Matched by class name, as in
   the operator consumer: Babashka cannot resolve the exception class."
  [e]
  (= "java.nio.channels.OverlappingFileLockException" (.getName (class e))))

(defn- ^{:stratum 0} server-info
  []
  {:pid (.pid (java.lang.ProcessHandle/current))
   :started (str (java.time.Instant/now))
   :operator-dir (str (es/operator-dir))})

(defn- ^{:stratum 0} refuse!
  "Report the server already running for `home`; exit code 1."
  [home pid]
  (binding [*out* *err*]
    (display/print-error (messages/t :operator-serve/already-running
                                     {:home (str home)
                                      :pid (or pid (messages/t :operator-serve/pid-unknown))})))
  1)

(defn- ^{:stratum 0} block-forever!
  []
  @(promise))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} try-lock!
  "An open channel holding `home`'s server lock, or nil when another
   server holds it. Closing the channel releases the lock (the only
   release Babashka permits)."
  [home]
  (let [f (io/file home lock-file-name)
        _ (io/make-parents f)
        channel (FileChannel/open (.toPath f)
                                  (into-array OpenOption [StandardOpenOption/CREATE
                                                          StandardOpenOption/WRITE]))
        lock (try (.tryLock channel)
                  (catch Exception e
                    (.close channel)
                    (when-not (overlapping-lock? e) (throw e))))]
    (if lock
      channel
      (do (.close channel) nil))))

(defn- ^{:stratum 1} write-discovery!
  "Write the discovery file whole (temp file, then an atomic rename), so
   a reader never sees half of it."
  [home info]
  (let [target (io/file home discovery-file-name)
        tmp (io/file home (str discovery-file-name ".tmp"))]
    (spit tmp (json/generate-string info))
    (Files/move (.toPath tmp) (.toPath target)
                (into-array java.nio.file.CopyOption [StandardCopyOption/ATOMIC_MOVE
                                                      StandardCopyOption/REPLACE_EXISTING]))))

(defn- ^{:stratum 1} stop-serving!
  "Stop the consumer (letting an in-flight pass finish), remove the
   discovery file, release the lock."
  [home ^FileChannel channel]
  (control/stop-process-control!)
  (io/delete-file (io/file home discovery-file-name) true)
  (.close channel))

(defn- ^{:stratum 1} running-pid
  [home]
  (try (:pid (json/parse-string (slurp (io/file home discovery-file-name)) true))
       (catch Exception _ nil)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} serve-cmd
  "Serve until `await-stop!` returns. From the CLI it never does: SIGTERM
   or SIGINT ends the process and the shutdown hook stops the consumer,
   removes the discovery file, and releases the lock — a clean stop that
   exits 143 (SIGTERM) or 130 (SIGINT). Returns the exit code otherwise:
   0 after `await-stop!` returns, 1 when a server already holds `home`."
  ([_opts]
   (shared/exit! (serve-cmd (config/miniforge-home) block-forever!)))
  ([home await-stop!]
   (if-let [channel (try-lock! home)]
     (let [info (server-info)
           stop! #(stop-serving! home channel)
           hook (Thread. ^Runnable stop!)]
       (.addShutdownHook (Runtime/getRuntime) hook)
       (control/start-process-control!)
       (write-discovery! home info)
       (println (json/generate-string (assoc info :ready true)))
       (flush)
       (await-stop!)
       (.removeShutdownHook (Runtime/getRuntime) hook)
       (stop!)
       0)
     (refuse! home (running-pid home)))))
