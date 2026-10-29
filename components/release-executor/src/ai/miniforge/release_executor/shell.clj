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
(ns ai.miniforge.release-executor.shell
  "Deadline-bounded runner for the host-mode release calls that talk to a
   remote: git fetch, git push, and gh.

   `babashka.process/shell` waits for the child with no deadline and
   inherits stdin. A stalled connection, or a credential prompt in an
   interactive terminal, then blocks the release phase for good; nothing
   above it enforces a phase deadline. `run-bounded` instead:

   - gives the child empty stdin and GIT_TERMINAL_PROMPT=0 /
     GH_PROMPT_DISABLED=1, so git and gh fail rather than prompt;
   - waits at most `timeout-ms`, then kills the child, its descendants, and
     any process seen as a descendant while it ran (git forks ssh and
     git-remote-https; killing git alone orphans them), and closes the
     output pipes so no reader thread is left blocked;
   - reports a timeout as data: a non-zero :exit, :timed-out? true, the
     message in :err, and a :timeout anomaly in :anomaly."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.release-executor.messages :as msg]
   [babashka.process :as process]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-timeout-ms
  "Deadline for one remote-facing git or gh call. Five minutes covers a
   large push over a slow link; a call still running after that is
   treated as hung."
  (* 5 60 1000))

(def ^{:stratum 0} timeout-exit-code
  "Exit code reported for a call killed at its deadline. Matches coreutils
   timeout(1) and dag-executor's runtime process helpers."
  124)

(def ^{:stratum 0} non-interactive-env
  "Environment that makes git and gh fail instead of waiting for terminal
   input. ssh reads passphrase and host-key prompts from /dev/tty, which
   these do not cover; the deadline bounds those."
  {"GIT_TERMINAL_PROMPT" "0"
   "GH_PROMPT_DISABLED"  "1"})

(defn ^{:stratum 0} parent-env
  "The environment children start from: this process's. A function so
   tests can supply a fixed environment."
  []
  (into {} (System/getenv)))

(defn ^{:stratum 0} trace-env-var?
  "True for git's tracing variables: GIT_TRACE, GIT_TRACE_* (CURL, PACKET,
   REDACT, ...), GIT_TRACE2*, and GIT_CURL_VERBOSE. With a credential in a
   child's environment, GIT_TRACE_CURL or GIT_CURL_VERBOSE can write the
   Authorization header to a log, and GIT_TRACE_REDACT=0 turns git's
   default redaction off."
  [var-name]
  (or (str/starts-with? var-name "GIT_TRACE")
      (= "GIT_CURL_VERBOSE" var-name)))

(def ^{:stratum 0} poll-ms
  "How often a running call's descendants are recorded. A helper whose
   parent exits (git-remote-https, a credential helper) is reparented and
   no longer listed as a descendant, so the kill set is collected while
   the tree is still intact."
  100)

(def ^{:stratum 0} drain-grace-ms
  "How long, after the kill, to wait for the stdout/stderr readers to end
   before cancelling them."
  2000)

(defn- ^{:stratum 0} descendants-of
  "Current descendants of `proc` as ProcessHandles."
  [^Process proc]
  (vec (.. proc descendants (toArray))))

(defn- ^{:stratum 0} command-label
  "First three argv words (\"git push -u\", \"gh pr create\"), for messages.
   Later words can carry a PR body, so they are left out."
  [args]
  (str/join " " (take 3 args)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} wait-recording-descendants
  "Wait up to `timeout-ms` for `done`, recording `proc`'s descendants every
   `poll-ms`. Returns [result observed-handles], with result ::timeout when
   the deadline passed first."
  [^Process proc done timeout-ms]
  (let [deadline (+ (System/currentTimeMillis) timeout-ms)]
    (loop [observed #{}]
      (let [observed (into observed (descendants-of proc))
            left     (- deadline (System/currentTimeMillis))
            result   (if (pos? left) (deref done (min poll-ms left) ::pending) ::timeout)]
        (if (= ::pending result)
          (recur observed)
          [result observed])))))

(defn- ^{:stratum 1} destroy-all!
  "Forcibly kill every recorded descendant still alive, `proc`'s current
   descendants, then `proc`. ProcessHandle checks the start time, so a
   recorded pid reused by an unrelated process is not killed. Same approach
   as phase-software-factory verify's helper; dag-executor's version is not
   on its public interface."
  [^Process proc observed]
  (doseq [^java.lang.ProcessHandle h (into (set observed) (descendants-of proc))]
    (.destroyForcibly h))
  (.destroyForcibly proc))

(defn- ^{:stratum 1} release-readers!
  "Close `proc`'s stdout/stderr pipes so the futures copying them end even
   when a process that escaped the kill still holds the write side, wait up
   to `drain-grace-ms`, then cancel whatever is left. Returns true when
   every reader ended on its own."
  [proc done]
  (let [^Process p (:proc proc)
        readers    [done (:out proc) (:err proc)]
        deadline   (+ (System/currentTimeMillis) drain-grace-ms)]
    (.close (.getInputStream p))
    (.close (.getErrorStream p))
    (loop []
      (cond
        (every? future-done? readers)           true
        (> (System/currentTimeMillis) deadline) (do (run! future-cancel readers) false)
        :else                                   (do (Thread/sleep 20) (recur))))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-bounded
  "Run argv `args` with empty stdin, `non-interactive-env`, and a deadline.

   opts: :dir, :extra-env (merged over `non-interactive-env`), :timeout-ms
   (default `default-timeout-ms`), :no-trace? (drop `trace-env-var?`
   variables from the child's environment; set it for any call that
   carries a credential). The child starts from `parent-env`.

   Returns {:exit :out :err} when the process finishes. At the deadline it
   kills the process, its descendants, and any process recorded as a
   descendant while it ran; closes the output pipes; and returns
   {:exit `timeout-exit-code` :out \"\" :err <message> :timed-out? true
   :anomaly <:timeout anomaly> :readers-done? <bool>}. :readers-done? is
   false when an output reader had to be cancelled. A process that cannot
   be started throws, as `process/shell` does; callers already catch that."
  [args {:keys [dir extra-env timeout-ms no-trace?]}]
  (let [timeout-ms (or timeout-ms default-timeout-ms)
        label      (command-label args)
        base-env   (cond->> (parent-env)
                     no-trace? (into {} (remove #(trace-env-var? (key %)))))
        proc       (apply process/process
                          (cond-> {:in  ""
                                   :out :string
                                   :err :string
                                   :env (merge base-env non-interactive-env extra-env)}
                            dir (assoc :dir (str dir)))
                          args)
        ;; bb's process record has no timed deref, so wait on a future.
        done       (future @proc)
        [finished observed] (wait-recording-descendants (:proc proc) done timeout-ms)]
    (if (not= ::timeout finished)
      (select-keys finished [:exit :out :err])
      (let [message       (msg/t :shell/timed-out {:command label :timeout-ms timeout-ms})
            _             (destroy-all! (:proc proc) observed)
            readers-done? (release-readers! proc done)]
        {:exit          timeout-exit-code
         :out           ""
         :err           message
         :timed-out?    true
         :readers-done? readers-done?
         :anomaly       (anomaly/anomaly :timeout message
                                         {:command label :timeout-ms timeout-ms})}))))
