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
(ns ai.miniforge.phase-software-factory.verify-output-limit-test
  "A local test run whose output passes the subprocess capture limit, or
   whose output a leftover process keeps open, fails with its diagnostic in
   the phase summary instead of filling the heap or passing on part of it."
  (:require
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.verify :as verify]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]
   [ai.miniforge.subprocess.interface :as subprocess]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing use-fixtures]])
  (:import
   (java.io PipedInputStream PipedOutputStream)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private limit-bytes
  "A capture limit `yes` passes with its first write."
  1024)

(def ^{:stratum 0} ^:private endless-test-cmd
  "A test command that writes to stdout until it is stopped."
  "yes")

(def ^{:stratum 0} ^:private endless-stderr-cmd
  "A test command that writes to stderr until it is stopped, with a line of
   stdout first."
  "echo started; yes err >&2")

(def ^{:stratum 0} ^:private passing-cmd
  "Prints a clojure.test summary that parses as a pass, and exits 0."
  "printf 'Ran 1 tests containing 1 assertions.\\n0 failures, 0 errors.\\n'")

(def ^{:stratum 0} ^:private short-settle-ms
  "Settle window for the held-open test, so it fails fast."
  200)

(def ^{:stratum 0} ^:private stop-deadline-ms
  "Test deadline far past the moment `yes` passes the limit, so a timed-out
   result means the run was never stopped."
  10000)

(def ^{:stratum 0} ^:private stop-slack-ms
  "Wall-clock allowance for the stop: the overflow, the process-tree kill
   and the output readers settling."
  5000)

(def ^{:stratum 0} ^:private bounded-drain
  "The component's drain, captured before the test redefines its var."
  subprocess/drain-stream)

(def ^{:stratum 0} ^:private phase-test-config-resource
  "config/phase/test-support-namespaces.edn")

(defn- ^{:stratum 0} message-heading
  "The first line of a phase message, before its output preview."
  [message-key params]
  (first (str/split (messages/t message-key (assoc params :head "")) #"\n")))

(defn- ^{:stratum 0} verify-context
  []
  {:execution/id             (random-uuid)
   :execution/environment-id (random-uuid)
   :execution/worktree-path  "/tmp/test-worktree"
   :execution/input          {:description "Test task" :title "Test" :intent "testing"}
   :execution/metrics        {:tokens 0 :duration-ms 0}
   :phase-config             {:phase :verify}})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} small-limit-drain
  [stream opts]
  (bounded-drain stream (assoc opts :max-bytes limit-bytes)))

(defn- ^{:stratum 1} held-open-drain
  "A drain over a pipe nobody closes, standing in for output a process the
   tests left behind keeps open. The JDK 21 reaper closes a child's pipe
   when the child exits, so a real leftover holds it open only on other
   JDKs (babashka's JDK 25 keeps it open); this makes the case JDK-neutral."
  [_stream opts]
  (bounded-drain (PipedInputStream. (PipedOutputStream.)) opts))

(deftest ^{:stratum 1} enter-verify-keeps-the-output-limit-message-test
  (testing "the phase summary and error carry the output-limit message, not
            a bare failure count"
    (let [message (messages/t :verify/output-limit
                              {:bytes limit-bytes :test-cmd endless-test-cmd :head "y\ny\n"})]
      (with-redefs [verify/run-tests! (fn [_path & _opts]
                                        {:passed? false :test-count 0 :assertion-count 0
                                         :fail-count 0 :error-count 1
                                         :output-limit? true :output message})]
        (let [result (get-in (verify/enter-verify (verify-context)) [:phase :result])]
          (is (= message (:summary result)))
          (is (str/includes? (str (get-in result [:error :message])) message)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} run-tests-stops-output-past-the-capture-limit-test
  (testing "a test command that writes without end is stopped at the capture
            limit and fails with a message implement can act on"
    (with-redefs [subprocess/drain-stream small-limit-drain]
      (let [started (System/currentTimeMillis)
            result  (verify/run-tests! "/tmp" :test-cmd endless-test-cmd
                                       :timeout-ms stop-deadline-ms)
            took    (- (System/currentTimeMillis) started)]
        (is (false? (:passed? result)))
        (is (true? (:output-limit? result)))
        (is (not (:timed-out? result)) "stopped at the limit, not at the deadline")
        (is (str/starts-with? (:output result)
                              (message-heading :verify/output-limit
                                               {:bytes limit-bytes :test-cmd endless-test-cmd})))
        (is (str/includes? (:output result) "y\ny\n") "the start of the output is shown")
        (is (< took stop-slack-ms) (str "returned after " took "ms"))))))

(deftest ^{:stratum 2} run-tests-shows-the-stream-that-passed-the-limit-test
  (testing "when stderr passes the limit, the failure shows stderr's start"
    (with-redefs [subprocess/drain-stream small-limit-drain]
      (let [result (verify/run-tests! "/tmp" :test-cmd endless-stderr-cmd
                                      :timeout-ms stop-deadline-ms)]
        (is (false? (:passed? result)))
        (is (str/includes? (:output result) "err\nerr\n"))))))

(deftest ^{:stratum 2} run-tests-fails-when-output-stays-open-after-exit-test
  (testing "a command that exits 0 does not pass the run while its output is
            still open, so what was read may be incomplete"
    (with-redefs-fn {#'verify/output-settle-ms short-settle-ms
                     #'subprocess/drain-stream held-open-drain}
      (fn []
        (let [result (verify/run-tests! "/tmp" :test-cmd passing-cmd
                                        :timeout-ms stop-deadline-ms)]
          (is (false? (:passed? result)))
          (is (true? (:output-unsettled? result)))
          (is (str/starts-with? (:output result)
                                (message-heading :verify/output-unsettled
                                                 {:ms short-settle-ms :test-cmd passing-cmd}))))))))

;------------------------------------------------------------------------------ Test Fixtures
(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (try
      (binding [loader/phase-loader-config-resource phase-test-config-resource]
        (f))
      (finally
        (phase/reset-phase-loader!)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-output-limit-test)
  :leave-this-here)
