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

(ns ai.miniforge.bb-proc.core-test
  "Unit tests for bb-proc. Exercises only on commands guaranteed to
   exist on any POSIX host (`true`, `false`, `echo`). No network, no
   filesystem writes, no sleeps beyond the tight `destroy!` deadline."
  (:require [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.miniforge.bb-proc.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Factories

(def ^:private ok-cmd   "true")
(def ^:private fail-cmd "false")

;------------------------------------------------------------------------------ Layer 1
;; Unit tests

(deftest test-sh-returns-exit-and-captures-stdout
  (testing "given a zero-exit command → :exit 0 and captured stdout"
    (let [result (sut/sh "echo" "hello-from-sh")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "hello-from-sh")))))

(deftest test-sh-does-not-throw-on-nonzero-exit
  (testing "given a non-zero-exit command → returns result, no throw"
    (let [result (sut/sh fail-cmd)]
      (is (= 1 (:exit result))))))

(deftest test-run!-throws-on-nonzero-exit
  (testing "given a failing command → throws ex-info with :exit and :cmd"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (sut/run! {:out :string :err :string} fail-cmd)))
          data (ex-data ex)]
      (is (= 1 (:exit data)))
      (is (= [fail-cmd] (vec (:cmd data)))))))

(deftest test-run!-returns-result-on-zero-exit
  (testing "given a zero-exit command → returns :exit 0"
    (let [result (sut/run! {:out :string :err :string} ok-cmd)]
      (is (zero? (:exit result))))))

(deftest test-installed-true-for-known-binary
  (testing "given `echo` → on PATH"
    (is (true? (sut/installed? "echo")))))

(deftest test-installed-false-for-missing-binary
  (testing "given a made-up name → not on PATH"
    (is (false? (sut/installed?
                 "definitely-not-a-real-binary-xyzzy-bb-proc")))))

(deftest test-run-bg!-starts-and-destroy!-tears-down
  (testing "given a long-running command → run-bg! returns handle, destroy! tears it down"
    (let [proc (sut/run-bg! {:out :string :err :string} "sleep" "30")]
      (is (some? proc))
      (sut/destroy! proc)
      ;; After destroy! the process should no longer be alive.
      (is (not (.isAlive (:proc proc)))))))

(deftest test-destroy!-handles-nil
  (testing "given nil → no-op"
    (is (nil? (sut/destroy! nil)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-proc.core-test)

  :leave-this-here)
