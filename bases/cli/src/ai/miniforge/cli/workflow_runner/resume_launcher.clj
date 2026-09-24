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
   `<home>/logs/resume-<run-id>.log`. It is started under `nohup` (and
   `setsid` where installed) as an asynchronous `/bin/sh` command, so a
   terminal Ctrl-C or hangup aimed at this process does not reach it,
   and it keeps running when this process exits. What it refuses, and
   why a redelivered intervention never spawns twice, is in
   `resume-records`."
  (:require
   [ai.miniforge.cli.workflow-runner.resume-records :as records]
   [clojure.java.io :as io]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private spawn-script
  ;; $1 is the log file; the rest is the command. `&` makes it an
  ;; asynchronous list of a non-interactive shell, which POSIX starts
  ;; with SIGINT/SIGQUIT ignored and stdin from /dev/null; `nohup`
  ;; ignores SIGHUP; `setsid` (Linux) also leaves this process group.
  ;; `$!` is the command's own pid: setsid and nohup exec it in place.
  (str "log=$1; shift; "
       "if command -v setsid >/dev/null 2>&1; "
       "then setsid nohup \"$@\" >>\"$log\" 2>&1 & "
       "else nohup \"$@\" >>\"$log\" 2>&1 & fi; echo $!"))

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
  [{:keys [command spawn!]} plan]
  (let [workflow-id (:resume/workflow-id plan)
        prior (records/launch-record workflow-id)
        origin (records/recorded-origin workflow-id)]
    (cond
      (= (str (:resume/intervention-id plan)) (:resume/intervention-id prior)) prior
      (records/launch-running? prior) (records/failure :conflict :resume-in-flight
                                                       {:resume/pid (:resume/pid prior)})
      (records/target-live? workflow-id) (records/failure :conflict :resume-target-live {})
      (nil? origin) (records/failure :not-found :resume-origin-unknown {})
      :else (records/start! plan #(spawn! (resume-argv command plan %1) %2 origin)))))

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
      {:launch! (partial launch! {:command prefix :spawn! spawn-detached!})})))
