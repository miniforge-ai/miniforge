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
(ns ai.miniforge.cli.workflow-runner.resume-launcher
  "The resume launcher `:retry` / `:retry-from-phase` interventions
   dispatch through (Phase D D-3b), registered by `workflow-runner.control`.

   `:launch!` runs inside the consumer's pass and only spawns: the
   retried run is its own `mf resume` process, started in the directory
   the run was started from, with output in
   `<home>/logs/resume-<run-id>.log`, under `nohup` and in a session
   (`setsid`) or process group (job control) of its own: a Ctrl-C, a
   hangup or a signal to this process's group does not reach it, and it
   outlives this process. What it refuses, and why a redelivered
   intervention never spawns twice, is in `resume-records`.

   `:await-start!` runs on the operator's verification pool, off the
   pass: it waits (bounded) for an event from this child — one carrying
   the intervention id as its correlation id. A child that exits first
   did not start; one still silent at the deadline is killed and did not
   start; an interrupted wait (the process stopping) leaves the child
   running and reports `:resume/pending?`, for the verification to be
   finished after a restart."
  (:require
   [ai.miniforge.cli.workflow-runner.resume-records :as records]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} observe-timeout-ms
  "How long a launched run gets, from its launch, to write its first
   event (startup takes seconds)."
  60000)

(def ^{:stratum 0} ^:private observe-poll-ms 250)

(def ^{:stratum 0} ^:private spawn-script
  ;; $1 is the log file; the rest is the command. `nohup` ignores
  ;; SIGHUP; stdin of an asynchronous list is /dev/null. `setsid`
  ;; (Linux) gives the command a session of its own; without it (macOS),
  ;; `set -m` turns on job control so the background job gets a process
  ;; group of its own. Never both: under job control the job leads its
  ;; group, and setsid would then fork and `$!` name the wrong process.
  ;; `$!` is the command's own pid: setsid and nohup exec it in place.
  (str "log=$1; shift; "
       "if command -v setsid >/dev/null 2>&1; "
       "then setsid nohup \"$@\" >>\"$log\" 2>&1 & "
       "else set -m; nohup \"$@\" >>\"$log\" 2>&1 & fi; echo $!"))

(defn ^{:stratum 0} self-command
  "The argv prefix that runs this CLI again: `MINIFORGE_CMD` when set,
   else this process's command line minus the CLI arguments it was given.
   Nil when neither is knowable — no command is guessed."
  [env-command command arguments cli-args]
  (let [n (count cli-args)]
    (cond
      (not (str/blank? env-command)) [env-command]
      (and command (pos? n) (= (seq cli-args) (seq (take-last n arguments))))
      (into [command] (drop-last n arguments)))))

(defn ^{:stratum 0} resume-argv [command plan run-id]
  (let [from-phase (:resume/from-phase plan)]
    (cond-> (into (vec command) ["resume" (str (:resume/workflow-id plan))
                                 "--run-id" (str run-id)
                                 "--correlation-id" (str (:resume/intervention-id plan))])
      from-phase (into ["--from-phase" (name from-phase)]))))

(defn- ^{:stratum 0} await-outcome
  "Poll to `:observed`, `:exited`, `:timeout` (past `deadline-ms`), or
   `:interrupted`."
  [{:keys [started? alive? deadline-ms poll-ms]}]
  (try
    (loop []
      (cond
        (started?) :observed
        ;; Re-check after death: a quick child can write and exit
        ;; between the two reads.
        (not (alive?)) (if (started?) :observed :exited)
        (> (System/currentTimeMillis) deadline-ms) :timeout
        :else (do (Thread/sleep ^long poll-ms) (recur))))
    (catch InterruptedException _ :interrupted)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} spawn-detached!
  "Start `argv` detached in `dir`, output appended to `log-file`; its pid."
  [argv log-file dir]
  (io/make-parents (io/file log-file))
  (let [builder (doto (ProcessBuilder. ^java.util.List
                                       (into ["/bin/sh" "-c" spawn-script "sh" (str log-file)] argv))
                  (.directory (io/file dir)))
        process (.start builder)
        out (slurp (.getInputStream process))]
    (.waitFor process)
    (parse-long (str/trim out))))

(defn ^{:stratum 1} launch!
  "`:launch!`: the launch for `plan`, or a failure anomaly. A redelivered
   intervention gets its recorded launch back and no second child; a
   retry is refused while another launch of the workflow is running,
   while the workflow has a live runner, or when its origin is unknown."
  [{:keys [command spawn! timeout-ms]} plan]
  (let [workflow-id (:resume/workflow-id plan)
        prior (records/launch-record workflow-id)
        origin (records/recorded-origin workflow-id)]
    (cond
      (= (str (:resume/intervention-id plan)) (:resume/intervention-id prior)) prior
      (records/launch-running? prior timeout-ms) (records/failure :conflict :resume-in-flight
                                                       {:resume/pid (:resume/pid prior)})
      (records/target-live? workflow-id) (records/failure :conflict :resume-target-live {})
      (nil? origin) (records/failure :not-found :resume-origin-unknown {})
      :else (records/start! plan #(spawn! (resume-argv command plan %1) %2 origin)))))

(defn ^{:stratum 1} await-start!
  "`:await-start!`: the launch once its child has shown itself; a
   failure anomaly naming why, with the child's log; or, when the wait
   is interrupted, `{:resume/pending? true}`."
  [{:keys [alive? kill! timeout-ms poll-ms]} launch]
  (let [{:resume/keys [run-id pid pid-started intervention-id launched-at-ms log]} launch
        outcome (await-outcome
                 {:started? #(records/correlated-event? run-id intervention-id launched-at-ms)
                  ;; No pid recorded (a crash between spawn and record):
                  ;; only the evidence or the deadline can decide.
                  :alive? #(and (not (:resume/exited? launch))
                                (or (nil? pid) (alive? pid pid-started)))
                  :deadline-ms (+ launched-at-ms timeout-ms)
                  :poll-ms poll-ms})
        details {:failure/reason outcome :failure/log log :resume/run-id run-id :resume/pid pid}]
    (when (= :timeout outcome) (kill! pid))
    (case outcome
      :observed launch
      :interrupted {:resume/pending? true}
      (records/failure :unavailable :resume-not-started details))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} launcher
  "The `operator/register-resume-launcher!` handle, or nil when this
   process cannot name the command that re-runs it. Call on the thread
   that got the CLI arguments: `*command-line-args*` is bound to it."
  []
  (let [info (.info (java.lang.ProcessHandle/current))
        arguments (vec (.orElse (.arguments info) (into-array String [])))]
    (when-let [prefix (self-command (System/getenv "MINIFORGE_CMD")
                                    (.orElse (.command info) nil)
                                    arguments
                                    *command-line-args*)]
      (let [deps {:command prefix
                  :spawn! spawn-detached!
                  :alive? records/process-running?
                  :kill! records/destroy-process!
                  :timeout-ms observe-timeout-ms
                  :poll-ms observe-poll-ms}]
        {:launch! (partial launch! deps)
         :await-start! (partial await-start! deps)
         :settle! records/settle!}))))
