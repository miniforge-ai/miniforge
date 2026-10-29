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
   - waits at most `timeout-ms`, then kills the child and every descendant
     (git forks ssh and git-remote-https; killing git alone orphans them);
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

(defn- ^{:stratum 0} destroy-process-tree!
  "Forcibly kill `proc` and its descendants, children first. Same approach
   as phase-software-factory verify's helper; dag-executor's version is not
   on its public interface."
  [^Process proc]
  (doseq [^java.lang.ProcessHandle child (.. proc descendants (toArray))]
    (.destroyForcibly child))
  (.destroyForcibly proc))

(defn- ^{:stratum 0} command-label
  "First three argv words (\"git push -u\", \"gh pr create\"), for messages.
   Later words can carry a PR body, so they are left out."
  [args]
  (str/join " " (take 3 args)))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} run-bounded
  "Run argv `args` with empty stdin, `non-interactive-env`, and a deadline.

   opts: :dir, :extra-env (merged over `non-interactive-env`), :timeout-ms
   (default `default-timeout-ms`).

   Returns {:exit :out :err} when the process finishes. At the deadline it
   kills the process tree and returns {:exit `timeout-exit-code` :out \"\"
   :err <message> :timed-out? true :anomaly <:timeout anomaly>}. A process
   that cannot be started throws, as `process/shell` does; callers already
   catch that."
  [args {:keys [dir extra-env timeout-ms]}]
  (let [timeout-ms (or timeout-ms default-timeout-ms)
        label      (command-label args)
        proc       (apply process/process
                          (cond-> {:in        ""
                                   :out       :string
                                   :err       :string
                                   :extra-env (merge non-interactive-env extra-env)}
                            dir (assoc :dir (str dir)))
                          args)
        ;; bb's process record has no timed deref, so wait on a future.
        done       (future @proc)
        finished   (deref done timeout-ms ::timeout)]
    (if (not= ::timeout finished)
      (select-keys finished [:exit :out :err])
      (let [message (msg/t :shell/timed-out {:command label :timeout-ms timeout-ms})]
        (destroy-process-tree! (:proc proc))
        ;; The output readers end once the pipes close. If a stray holder
        ;; keeps one open, stop waiting rather than leak this thread.
        (when (= ::timeout (deref done 5000 ::timeout))
          (future-cancel done))
        {:exit       timeout-exit-code
         :out        ""
         :err        message
         :timed-out? true
         :anomaly    (anomaly/anomaly :timeout message
                                      {:command label :timeout-ms timeout-ms})}))))
