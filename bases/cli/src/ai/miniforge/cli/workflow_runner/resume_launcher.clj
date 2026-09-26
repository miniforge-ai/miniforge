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
   outlives this process. Before it runs the command, the child writes
   its own pid to `<home>/logs/resume-<run-id>.pid`, so it can be found
   and killed even when this process dies before recording it. What it
   refuses, and why a redelivered intervention never spawns twice, is in
   `resume-records`.

   Native Windows has no `/bin/sh` to detach through, so there is no
   launcher there: a retry fails `:no-resume-launcher`.

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
  ;; $1 is the log file, $2 the pid file; the rest is the command.
  ;; `nohup` ignores SIGHUP; stdin of an asynchronous list is /dev/null.
  ;; `setsid` (Linux) gives the command a session of its own; without it
  ;; (macOS), `set -m` turns on job control so the background job gets a
  ;; process group of its own. Never both: under job control the job
  ;; leads its group, and setsid would then fork and `$!` name the wrong
  ;; process. The inner shell writes its pid (whole: a temp file, then a
  ;; rename), then execs the command in place, so the pid file names the
  ;; command. `$!` is the same pid:
  ;; setsid and nohup exec in place too.
  (str "log=$1; pidfile=$2; shift 2; child='echo $$ >\"$0.tmp\" && mv \"$0.tmp\" \"$0\"; exec \"$@\"'; "
       "if command -v setsid >/dev/null 2>&1; "
       "then setsid nohup /bin/sh -c \"$child\" \"$pidfile\" \"$@\" >>\"$log\" 2>&1 & "
       "else set -m; nohup /bin/sh -c \"$child\" \"$pidfile\" \"$@\" >>\"$log\" 2>&1 & fi; echo $!"))

(defn ^{:stratum 0} detachable-platform?
  "True where a child can be detached through `/bin/sh`: not native
   Windows."
  [os-name]
  (not (str/starts-with? (str/lower-case (str os-name)) "windows")))

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
   `:interrupted`. A check that throws counts as no evidence and a live
   child for that poll, so only the deadline ends a wait that keeps
   failing."
  [{:keys [started? alive? deadline-ms poll-ms]}]
  (let [poll (fn [check failed]
               (try (check)
                    (catch InterruptedException e (throw e))
                    (catch Exception _ failed)))
        started? #(poll started? false)
        alive? #(poll alive? true)]
    (try
      (loop []
        (cond
          (started?) :observed
          ;; Re-check after death: a quick child can write and exit
          ;; between the two reads.
          (not (alive?)) (if (started?) :observed :exited)
          (> (System/currentTimeMillis) deadline-ms) :timeout
          :else (do (Thread/sleep ^long poll-ms) (recur))))
      (catch InterruptedException _ :interrupted))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} spawn-detached!
  "Start `argv` detached in `dir`, output appended to `log-file`; its pid,
   which the child also writes to `pid-file` before it runs `argv`."
  [argv log-file pid-file dir]
  (io/make-parents (io/file log-file))
  (io/make-parents (io/file pid-file))
  (let [builder (doto (ProcessBuilder. ^java.util.List
                                       (into ["/bin/sh" "-c" spawn-script "sh" (str log-file) (str pid-file)]
                                             argv))
                  (.directory (io/file dir)))
        process (.start builder)
        out (slurp (.getInputStream process))]
    (.waitFor process)
    (parse-long (str/trim out))))

(defn ^{:stratum 1} launch!
  "`:launch!`: the launch for `plan`, or a failure anomaly. A redelivered
   intervention gets its recorded launch back and no second child. Checks
   cover the workflow's whole lineage (see `resume-records`): a retry is
   refused while any launch in it is running, when an attempt of the
   workflow has since run (naming the newest, to retry instead), while
   any member has a live runner, or when its origin is unknown."
  [{:keys [command spawn! timeout-ms]} plan]
  (let [workflow-id (str (:resume/workflow-id plan))
        root (records/lineage-root workflow-id)
        prior (records/launch-record root)
        latest (records/latest-attempt prior)
        origin (or (records/recorded-origin workflow-id) (records/recorded-origin root))]
    (cond
      (= (str (:resume/intervention-id plan)) (:resume/intervention-id prior)) prior
      (records/launch-running? prior timeout-ms) (records/failure :conflict :resume-in-flight
                                                       {:resume/pid (:resume/pid prior)})
      (and latest (not= latest workflow-id)) (records/failure :conflict :resume-superseded
                                                              {:resume/latest-attempt latest})
      (some records/target-live? (cons root (:resume/attempts prior)))
      (records/failure :conflict :resume-target-live {})
      (nil? origin) (records/failure :not-found :resume-origin-unknown {})
      :else (records/start! (assoc plan :resume/root root :resume/attempts (:resume/attempts prior))
                            #(spawn! (resume-argv command plan %1) %2 %3 origin)))))

(defn ^{:stratum 1} await-start!
  "`:await-start!`: the launch once its child has shown itself; a
   failure anomaly naming why, with the child's log; or, when the wait
   is interrupted, `{:resume/pending? true}`. A child whose pid was
   never recorded is found by its pid file."
  [{:keys [alive? kill! timeout-ms poll-ms]} launch]
  (let [{:resume/keys [run-id pid pid-started exited? intervention-id launched-at-ms log]}
        (records/with-child-pid launch)
        outcome (await-outcome
                 {:started? #(records/correlated-event? run-id intervention-id launched-at-ms)
                  ;; No pid known yet (the child has not written it):
                  ;; only the evidence or the deadline can decide.
                  :alive? #(and (not exited?) (or (nil? pid) (alive? pid pid-started)))
                  :deadline-ms (+ launched-at-ms timeout-ms)
                  :poll-ms poll-ms})
        ;; Read again at the deadline: a child may write its pid late.
        pid (or pid (:resume/pid (records/with-child-pid launch)))
        details {:failure/reason outcome :failure/log log :resume/run-id run-id :resume/pid pid}]
    (when (and pid (= :timeout outcome)) (kill! pid))
    (case outcome
      :observed launch
      :interrupted {:resume/pending? true}
      (records/failure :unavailable :resume-not-started details))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} launcher
  "The `operator/register-resume-launcher!` handle, or nil on native
   Windows or when this process cannot name the command that re-runs it.
   Call on the thread that got the CLI arguments: `*command-line-args*`
   is bound to it. The one-arity form takes those facts as
   `{:os-name :env-command :command :arguments :cli-args}`."
  ([]
   (let [info (.info (java.lang.ProcessHandle/current))]
     (launcher {:os-name (System/getProperty "os.name")
                :env-command (System/getenv "MINIFORGE_CMD")
                :command (.orElse (.command info) nil)
                :arguments (vec (.orElse (.arguments info) (into-array String [])))
                :cli-args *command-line-args*})))
  ([{:keys [os-name env-command command arguments cli-args]}]
   (when-let [prefix (and (detachable-platform? os-name)
                          (self-command env-command command arguments cli-args))]
     (let [deps {:command prefix
                 :spawn! spawn-detached!
                 :alive? records/process-running?
                 :kill! records/destroy-process!
                 :timeout-ms observe-timeout-ms
                 :poll-ms observe-poll-ms}]
       {:launch! (partial launch! deps)
        :await-start! (partial await-start! deps)
        :settle! records/settle!}))))
