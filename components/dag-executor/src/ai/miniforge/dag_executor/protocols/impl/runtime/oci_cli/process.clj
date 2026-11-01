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
(ns ai.miniforge.dag-executor.protocols.impl.runtime.oci-cli.process
  "Runtime CLI invocation for the OCI-CLI executor: the command line for a
   descriptor, timed runs of it, and the default timeouts."
  (:require
   [ai.miniforge.dag-executor.protocols.impl.runtime.descriptor :as descriptor]
   [ai.miniforge.dag-executor.protocols.impl.runtime.messages :as messages]
   [ai.miniforge.dag-executor.protocols.impl.runtime.process :as runtime-process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-stop-timeout
  "Minimum graceful-stop timeout (seconds). Also the floor for the timeout
   computed from an execution plan's :time-limit-ms — `--stop-timeout`
   never goes below this."
  5)

(def ^{:stratum 0} default-exec-timeout-ms
  "Ceiling on a single `docker exec` invocation (`execute!`). Five minutes
   covers long-running agent commands while still bounding a hung pty or
   container-namespace teardown. Companion follow-up to the 2026-05-16
   dogfood fix: `acquire-environment!` was bounded by PR #895;
   `execute!`, `release-environment!`, `copy-to!`, and `copy-from!`
   are bounded here."
  300000)

(def ^{:stratum 0} default-copy-timeout-ms
  "Ceiling on a `docker exec`-based file-copy operation (`copy-to!` /
   `copy-from!`). Two minutes accommodates large workspace transfers
   while preventing an indefinite hang if Docker stalls mid-pipe."
  120000)

(def ^{:stratum 0} default-release-timeout-ms
  "Ceiling on container stop + remove during `release-environment!`.
   Two minutes bounds the stop/rm sequence that can hang on pty orphans
   or container-namespace teardown."
  120000)

;; ============================================================================
;; Helper Functions
;; ============================================================================
(defn ^{:stratum 0} runtime-cmd
  "Build a runtime CLI invocation vector for the given descriptor."
  [descriptor & args]
  (apply vector (descriptor/executable descriptor) args))

(defn ^{:stratum 0} shell-quote
  "Single-quote a shell argument."
  [s]
  (str "'" (str/replace s "'" "'\"'\"'") "'"))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-runtime-timed
  "Execute a runtime CLI command with an optional per-subprocess waitFor
   deadline.

   When `timeout-ms` is nil or <= 0 the call is unbounded (identical to the
   pre-fix behaviour). On timeout the process tree is destroyed forcibly and
   {:exit 124 :out \"\" :err <message>} is returned; callers that check
   `(zero? :exit)` surface this as a command failure without special-casing."
  [timeout-ms descriptor & args]
  (try
    (let [pb (ProcessBuilder. (into-array String (apply runtime-cmd descriptor args)))
          process (.start pb)
          stdout-fut (runtime-process/read-stream-future (.getInputStream process))
          stderr-fut (runtime-process/read-stream-future (.getErrorStream process))]
      (try
        (let [timed?     (and timeout-ms (pos? timeout-ms))
              completed? (if timed?
                           (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                           (do (.waitFor process) true))]
          (if-not completed?
            (do
              (runtime-process/destroy-process-tree! process)
              (future-cancel stdout-fut)
              (future-cancel stderr-fut)
              (binding [*out* *err*]
                (println (messages/t :oci/command-timeout-log {:ms timeout-ms})))
              {:exit 124
               :out  ""
               :err  (messages/t :oci/command-timeout {:ms timeout-ms})})
            (let [stdout-bytes @stdout-fut
                  stderr-bytes @stderr-fut]
              {:exit (.exitValue process)
               :out  (String. ^bytes stdout-bytes "UTF-8")
               :err  (String. ^bytes stderr-bytes "UTF-8")})))
        (catch InterruptedException e
          (runtime-process/destroy-process-tree! process)
          (future-cancel stdout-fut)
          (future-cancel stderr-fut)
          (.interrupt (Thread/currentThread))
          {:exit 130 :err (or (ex-message e) (messages/t :oci/command-interrupted)) :out ""})))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out ""})))

(defn ^{:stratum 1} run-runtime-process-timed
  "Execute a runtime CLI command with optional stdin bytes and an optional
   per-subprocess waitFor deadline.

   When `timeout-ms` is nil or <= 0 the call is unbounded. On timeout the
   process tree is destroyed forcibly and a result with :exit 124 is returned
   so callers that check `(zero? :exit)` surface it as a command failure."
  [timeout-ms descriptor args & {:keys [stdin-bytes]}]
  (try
    (let [pb (ProcessBuilder. (into-array String (apply runtime-cmd descriptor args)))
          process (.start pb)
          stdout-fut (runtime-process/read-stream-future (.getInputStream process))
          stderr-fut (runtime-process/read-stream-future (.getErrorStream process))
          stdin-fut  (when stdin-bytes
                       (let [s (.getOutputStream process)]
                         (future
                           (try
                             (.write s ^bytes stdin-bytes)
                             (finally (.close s))))))]
      (try
        (let [timed?     (and timeout-ms (pos? timeout-ms))
              completed? (if timed?
                           (.waitFor process timeout-ms java.util.concurrent.TimeUnit/MILLISECONDS)
                           (do (.waitFor process) true))]
          (if-not completed?
            (do
              (when stdin-fut (future-cancel stdin-fut))
              (runtime-process/destroy-process-tree! process)
              (future-cancel stdout-fut)
              (future-cancel stderr-fut)
              (binding [*out* *err*]
                (println (messages/t :oci/command-timeout-log {:ms timeout-ms})))
              {:exit      124
               :out       ""
               :out-bytes (byte-array 0)
               :err       (messages/t :oci/command-timeout {:ms timeout-ms})
               :err-bytes (byte-array 0)})
            (let [stdout-bytes @stdout-fut
                  stderr-bytes @stderr-fut]
              {:exit      (.exitValue process)
               :out-bytes stdout-bytes
               :err-bytes stderr-bytes
               :out       (String. ^bytes stdout-bytes "UTF-8")
               :err       (String. ^bytes stderr-bytes "UTF-8")})))
        (catch InterruptedException e
          (when stdin-fut (future-cancel stdin-fut))
          (runtime-process/destroy-process-tree! process)
          (future-cancel stdout-fut)
          (future-cancel stderr-fut)
          (.interrupt (Thread/currentThread))
          {:exit      130
           :err       (or (ex-message e) (messages/t :oci/command-interrupted))
           :out       ""
           :out-bytes (byte-array 0)
           :err-bytes (byte-array 0)})))
    (catch Exception e
      {:exit 1 :err (.getMessage e) :out "" :out-bytes (byte-array 0) :err-bytes (byte-array 0)})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-runtime
  "Execute a runtime CLI command and return the result.
   Internal callers that need a bounded deadline use `run-runtime-timed`
   directly; this public entry point preserves the unbounded signature
   for backward-compatibility with call sites already covered by an outer
   `with-acquisition-timeout` guard."
  [descriptor & args]
  (apply run-runtime-timed nil descriptor args))

(defn ^{:stratum 2} run-runtime-process
  "Execute a runtime CLI command with optional stdin bytes.
   Internal callers that need a bounded deadline use `run-runtime-process-timed`
   directly; this public entry point is unbounded for backward-compatibility."
  [descriptor args & {:keys [stdin-bytes]}]
  (run-runtime-process-timed nil descriptor args :stdin-bytes stdin-bytes))
