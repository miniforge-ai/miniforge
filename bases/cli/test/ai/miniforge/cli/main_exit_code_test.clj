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
(ns ai.miniforge.cli.main-exit-code-test
  "`mf resume`, `mf workflow run` and `mf chain run` exit non-zero whenever
   the run did not succeed or the command was misused, so scripts and CI can
   tell a failed run from a finished one."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main :as sut]
   [ai.miniforge.cli.main.commands.resume :as cmd-resume]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [ai.miniforge.cli.main.display :as display]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} exit-code
  "Call `(cmd-fn {:opts opts})` with every runner stubbed by `outcome`: a
   result map to return, or :throw to throw. Returns the code passed to
   exit!, or :no-exit."
  [cmd-fn opts outcome]
  (let [code (atom :no-exit)
        run (fn [& _]
              (if (= :throw outcome)
                (throw (ex-info "runner blew up" {}))
                outcome))]
    (with-redefs [shared/exit! (fn [c] (reset! code c) nil)
                  display/print-info (fn [& _])
                  display/print-error (fn [& _])
                  workflow-runner/run-workflow! run
                  workflow-runner/run-chain! run
                  cmd-resume/resume-workflow run]
      (binding [*out* (java.io.StringWriter.)]
        (cmd-fn {:opts opts})))
    @code))

(defn- ^{:stratum 0} resume-failure-output
  "Run `mf resume wf-1` with resume-workflow calling `throw!`, and the
   real error printers. Returns {:exit code-or-:no-exit :out stdout
   :err stderr}."
  [throw!]
  (let [code (atom :no-exit)
        out  (java.io.StringWriter.)
        err  (java.io.StringWriter.)]
    (with-redefs [shared/exit! (fn [c] (reset! code c) nil)
                  cmd-resume/resume-workflow (fn [& _] (throw!))]
      (binding [*out* out
                *err* err]
        (sut/resume-cmd {:opts {:workflow-id "wf-1"}})))
    {:exit @code :out (str out) :err (str err)}))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} workflow-run-exit-code-test
  (let [opts {:workflow-id "wf"}]
    (testing "a completed workflow returns normally (exit 0)"
      (is (= :no-exit (exit-code sut/workflow-run-cmd opts {:execution/status :completed}))))
    (testing "a failed workflow exits 1"
      (is (= 1 (exit-code sut/workflow-run-cmd opts {:execution/status :failed}))))
    (testing "a workflow that throws exits 1"
      (is (= 1 (exit-code sut/workflow-run-cmd opts :throw))))
    (testing "a missing workflow id exits 1"
      (is (= 1 (exit-code sut/workflow-run-cmd {} {:execution/status :completed}))))))

(deftest ^{:stratum 1} chain-run-exit-code-test
  (let [opts {:chain-id "c"}]
    (testing "a completed chain returns normally (exit 0)"
      (is (= :no-exit (exit-code sut/chain-run-cmd opts {:chain/status :completed}))))
    (testing "a failed chain exits 1"
      (is (= 1 (exit-code sut/chain-run-cmd opts {:chain/status :failed}))))
    (testing "a chain that throws exits 1"
      (is (= 1 (exit-code sut/chain-run-cmd opts :throw))))
    (testing "a missing chain id exits 1"
      (is (= 1 (exit-code sut/chain-run-cmd {} {:chain/status :completed}))))))

(deftest ^{:stratum 1} resume-exit-code-test
  (let [opts {:workflow-id "wf-1"}]
    (testing "a resumed run that completes returns normally (exit 0)"
      (is (= :no-exit (exit-code sut/resume-cmd opts {:execution/status :completed}))))
    (testing "a resumed run that ends failed exits 1"
      (is (= 1 (exit-code sut/resume-cmd opts {:execution/status :failed}))))
    (testing "resuming a run that is already complete is a no-op, not a failure"
      (is (= :no-exit (exit-code sut/resume-cmd opts nil))))
    (testing "a resume that throws exits 1"
      (is (= 1 (exit-code sut/resume-cmd opts :throw))))
    (testing "a missing workflow id exits 1"
      (is (= 1 (exit-code sut/resume-cmd {} {:execution/status :completed}))))))

(deftest ^{:stratum 1} resume-failure-is-reported-on-stderr-test
  (testing "a refused resume prints the anomaly's message on stderr, exits 1"
    (let [{:keys [exit out err]}
          (resume-failure-output
           #(response/throw-anomaly! :anomalies/not-found
                                     "No events found for workflow wf-1"
                                     {:workflow-id "wf-1"}))]
      (is (= 1 exit))
      (is (str/includes? err "Resume failed: No events found for workflow wf-1"))
      (is (not (str/includes? err "throw+")) "the message, not slingshot's wrapper text")
      (is (not (str/includes? err "Task Code Error")) "no catch-all classification")
      (is (= "" out))))

  (testing "an error the classifier recognises is shown classified, on stderr"
    (let [{:keys [exit out err]}
          (resume-failure-output
           #(throw (ex-info "handoff failed for agent implementer" {})))]
      (is (= 1 exit))
      (is (str/includes? err "Agent System Error"))
      (is (str/includes? err "handoff failed for agent implementer"))
      (is (= "" out))))

  (testing "an interrupt or a JVM error propagates instead of being reported"
    (is (thrown? StackOverflowError
                 (resume-failure-output #(throw (StackOverflowError.)))))
    ;; Read the flag before any `is`: clojure.test's reporting clears it.
    (let [thrown (try
                   (resume-failure-output #(throw (InterruptedException.)))
                   nil
                   (catch InterruptedException e e))
          interrupted? (Thread/interrupted)]
      (is (instance? InterruptedException thrown))
      (is interrupted? "the interrupt flag is restored"))))
