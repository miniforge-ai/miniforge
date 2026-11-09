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
  "A local test run whose output passes the subprocess capture limit is
   stopped and fails, instead of filling the heap."
  (:require
   [ai.miniforge.phase-software-factory.messages :as messages]
   [ai.miniforge.phase-software-factory.verify :as verify]
   [ai.miniforge.subprocess.interface :as subprocess]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

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

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} small-limit-drain
  [stream opts]
  (bounded-drain stream (assoc opts :max-bytes limit-bytes)))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} run-tests-stops-output-past-the-capture-limit-test
  (testing "a test command that writes without end is stopped at the capture
            limit and fails with a message implement can act on"
    (with-redefs [subprocess/drain-stream small-limit-drain]
      (let [started (System/currentTimeMillis)
            result  (verify/run-tests! "/tmp" :test-cmd endless-test-cmd
                                       :timeout-ms stop-deadline-ms)
            took    (- (System/currentTimeMillis) started)
            heading (first (str/split (messages/t :verify/output-limit
                                                  {:bytes    limit-bytes
                                                   :test-cmd endless-test-cmd
                                                   :head     ""})
                                      #"\n"))]
        (is (false? (:passed? result)))
        (is (not (:timed-out? result)) "stopped at the limit, not at the deadline")
        (is (str/starts-with? (:output result) heading))
        (is (str/includes? (:output result) "y\ny\n") "the start of the output is shown")
        (is (< took stop-slack-ms) (str "returned after " took "ms"))))))

(deftest ^{:stratum 2} run-tests-shows-the-stream-that-passed-the-limit-test
  (testing "when stderr passes the limit, the failure shows stderr's start"
    (with-redefs [subprocess/drain-stream small-limit-drain]
      (let [result (verify/run-tests! "/tmp" :test-cmd endless-stderr-cmd
                                      :timeout-ms stop-deadline-ms)]
        (is (false? (:passed? result)))
        (is (str/includes? (:output result) "err\nerr\n"))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.phase-software-factory.verify-output-limit-test)
  :leave-this-here)
