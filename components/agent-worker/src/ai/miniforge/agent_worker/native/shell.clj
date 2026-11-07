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
(ns ai.miniforge.agent-worker.native.shell
  "The POSIX shell lines a native worker runs inside the environment. Every
   argument is single-quoted; the prompt travels in a file, never on a
   command line."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sigterm-exit
  "Exit status of a process ended by SIGTERM (128 + 15): a cancelled turn."
  143)

(def ^{:stratum 0} run-root
  "Directory under the workdir that holds each turn's files."
  ".miniforge/worker")

(def ^{:stratum 0} env-name
  "A POSIX environment variable name; anything else never reaches a script."
  #"[A-Za-z_][A-Za-z0-9_]*")

(defn ^{:stratum 0} quote-arg
  [s]
  (str "'" (str/replace (str s) "'" "'\\''") "'"))

(defn ^{:stratum 0} turn-file [dir file] (str dir "/" file))

(defn ^{:stratum 0} process-ref [dir pid] (str dir "#" pid))

(defn ^{:stratum 0} split-ref
  "`[dir pid]` from a process reference."
  [ref]
  (str/split ref #"#" 2))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-dir
  [workdir attempt turn]
  (str workdir "/" run-root "/" attempt "-" turn))

(defn ^{:stratum 1} status-of
  "A Status from the status script's output."
  [out]
  (let [line (str/trim (str out))]
    (cond
      (= "running" line) {:status/state :state/running :status/exit nil}
      (re-matches #"\d+" line) (let [code (parse-long line)]
                                 {:status/state (cond (zero? code) :state/completed
                                                      (= sigterm-exit code) :state/cancelled
                                                      :else :state/failed)
                                  :status/exit code})
      :else {:status/state :state/unknown :status/exit nil})))

(defn ^{:stratum 1} command-line [argv] (str/join " " (map quote-arg argv)))

(defn ^{:stratum 1} status-script
  [dir pid]
  (str "if [ -f " (quote-arg (turn-file dir "exit")) " ]; then cat " (quote-arg (turn-file dir "exit"))
       "; elif kill -0 " (quote-arg pid) " 2>/dev/null; then echo running; else echo gone; fi"))

(defn ^{:stratum 1} cancel-script
  "Signal the harness, not the wrapper, so the wrapper records its exit."
  [pid]
  (str "pkill -TERM -P " (quote-arg pid) " 2>/dev/null; true"))

(defn ^{:stratum 1} present-script [cmd] (str "command -v " (quote-arg cmd) " > /dev/null"))

(defn ^{:stratum 1} credential-script
  "Exit zero when any of `names` is set and non-empty, printing nothing."
  [names]
  (let [safe (sort (filter #(re-matches env-name %) names))]
    (str "test -n \"" (str/join (map #(str "${" % ":-}") safe)) "\"")))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} launch-script
  "Run `argv` in `workdir` in the background with the turn's prompt on stdin,
   its stream and errors in files, and its exit status written when it ends;
   print the background pid."
  [workdir dir argv]
  (str "cd " (quote-arg workdir) " && ( " (command-line argv)
       " < " (quote-arg (turn-file dir "prompt.txt"))
       " > " (quote-arg (turn-file dir "out.jsonl"))
       " 2> " (quote-arg (turn-file dir "err.log"))
       "; echo $? > " (quote-arg (turn-file dir "exit")) " ) > /dev/null 2>&1 & echo $!"))
