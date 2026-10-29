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
(ns ai.miniforge.agent.artifact-session-interrupt-test
  "An interrupted agent call (the implement watchdog interrupts the phase
   thread; the LLM client rethrows with the interrupt flag set) unwinds
   through the capsule session's cleanup. The executor waits on
   `docker exec` interruptibly, so with the flag set the session
   directory was never removed. Cleanup must run to completion and the
   flag must still be set afterwards."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.agent.artifact-session :as session]
   [ai.miniforge.agent.file-artifacts :as file-artifacts]
   [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private capsule-workdir
  "Workdir of the fake capsule."
  "/workspace")

(def ^{:stratum 0} ^:private capsule-cleanup-command
  "The command that removes the session directory under `capsule-workdir`."
  (str "rm -rf " (file-artifacts/shell-quote "/workspace/.miniforge-session")))

(defn- ^{:stratum 0} interruptible-execute!
  "Fake executor that behaves like the OCI runtime under an interrupt: it
   waits interruptibly before the command runs (a zero-count latch still
   throws when the flag is set, like `Process.waitFor`), then records the
   command."
  [log]
  (fn [_executor _env-id command & [_opts]]
    (.await (java.util.concurrent.CountDownLatch. 0))
    (swap! log conj command)
    (result/ok {:stdout "" :stderr "" :exit-code 0})))

(defn- ^{:stratum 0} interrupted-agent-call!
  "Body that fails the way an interrupted stream-exec-fn does: flag set,
   InterruptedException thrown."
  [_session]
  (.interrupt (Thread/currentThread))
  (throw (InterruptedException. "agent stream interrupted")))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} interrupted-agent-call-still-removes-capsule-session-test
  (testing "capsule session cleanup completes under an interrupt, and the flag survives it"
    (let [log (atom [])
          context {:execution/mode :governed
                   :execution/executor :mock
                   :execution/environment-id "env-interrupt"
                   :execution/worktree-path capsule-workdir
                   :execution/execute-fn (interruptible-execute! log)}
          thrown (try
                   (session/with-session context interrupted-agent-call!)
                   nil
                   (catch InterruptedException e
                     e))
          ;; Read and clear, so the flag does not leak into later tests.
          flag-restored? (Thread/interrupted)]
      (is (instance? InterruptedException thrown)
          "the interruption still reaches the caller")
      (is (some #{capsule-cleanup-command} @log)
          "the session directory removal ran to completion")
      (is (true? flag-restored?)
          "the interrupt flag is set again after the cleanup"))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.agent.artifact-session-interrupt-test)
  :leave-this-here)
