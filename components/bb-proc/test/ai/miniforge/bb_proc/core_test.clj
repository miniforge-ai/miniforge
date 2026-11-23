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
   exist on any POSIX host (`true`, `false`, `echo`, `sh`, `sleep`). No
   network, no filesystem writes, no sleeps beyond the tight `destroy!`
   deadline."
  (:require [babashka.fs :as fs]
            [clojure.test :refer [deftest testing is]]
            [clojure.string :as str]
            [ai.miniforge.bb-proc.core :as sut]))

;------------------------------------------------------------------------------ Layer 0

;; Factories
(defn- ^{:stratum 0} required-command
  "Resolve a required POSIX command to an absolute path for deterministic tests."
  [cmd]
  (or (some-> (fs/which cmd) str)
      cmd))

;; Unit tests
(deftest ^{:stratum 0} test-sh-returns-exit-and-captures-stdout
  (testing "given a zero-exit command → :exit 0 and captured stdout"
    (let [result (sut/sh "echo" "hello-from-sh")]
      (is (zero? (:exit result)))
      (is (str/includes? (:out result) "hello-from-sh")))))

(deftest ^{:stratum 0} test-installed-true-for-known-binary
  (testing "given `echo` → on PATH"
    (is (true? (sut/installed? "echo")))))

(deftest ^{:stratum 0} test-installed-false-for-missing-binary
  (testing "given a made-up name → not on PATH"
    (is (false? (sut/installed?
                 "definitely-not-a-real-binary-xyzzy-bb-proc")))))

(deftest ^{:stratum 0} test-command-candidates-add-windows-clojure-fallbacks
  (testing "given clojure on windows → includes executable fallbacks"
    (is (= ["clojure" "clojure.exe" "clj.exe" "deps.exe"]
           (sut/command-candidates "clojure" :windows)))))

(deftest ^{:stratum 0} test-command-candidates-keep-other-cases-unchanged
  (testing "given clojure on unix → keeps the canonical command only"
    (is (= ["clojure"]
           (sut/command-candidates "clojure" :unix))))
  (testing "given a non-clojure command on windows → no extra fallbacks"
    (is (= ["bb"]
           (sut/command-candidates "bb" :windows)))))

(deftest ^{:stratum 0} test-first-resolved-command-prefers-first-hit
  (testing "given multiple candidates → returns the first resolved path"
    (let [lookup {"clojure.exe" "C:/tools/clojure.exe"
                  "deps.exe" "C:/tools/deps.exe"}]
      (is (= "C:/tools/clojure.exe"
             (sut/first-resolved-command
              ["clojure" "clojure.exe" "deps.exe"]
              lookup))))))

(deftest ^{:stratum 0} test-first-resolved-command-falls-back-to-original-command
  (testing "given no resolved candidates → returns the first candidate"
    (is (= "clojure"
           (sut/first-resolved-command ["clojure" "clojure.exe"] (constantly nil))))))

(deftest ^{:stratum 0} test-run-bg!-starts-and-destroy!-tears-down
  (testing "given a long-running command → run-bg! returns handle, destroy! tears it down"
    (let [proc (sut/run-bg! {:out :string :err :string} "sleep" "30")]
      (is (some? proc))
      (sut/destroy! proc)
      ;; After destroy! the process should no longer be alive.
      (is (not (.isAlive (:proc proc)))))))

(deftest ^{:stratum 0} test-destroy!-handles-nil
  (testing "given nil → no-op"
    (is (nil? (sut/destroy! nil)))))

;------------------------------------------------------------------------------ Layer 1

(def ^{:stratum 1} ^:private ok-cmd
  (required-command "true"))

(def ^{:stratum 1} ^:private fail-cmd
  (required-command "false"))

(def ^{:stratum 1} ^:private shell-cmd
  (required-command "sh"))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} test-sh-does-not-throw-on-nonzero-exit
  (testing "given a non-zero-exit command → returns result, no throw"
    (let [result (sut/sh fail-cmd)]
      (is (= 1 (:exit result))))))

(deftest ^{:stratum 2} test-run!-throws-on-nonzero-exit
  (testing "given a failing command → throws ex-info with :exit and :cmd"
    (let [ex (is (thrown? clojure.lang.ExceptionInfo
                          (sut/run! {:out :string :err :string} fail-cmd)))
          data (ex-data ex)]
      (is (= 1 (:exit data)))
      (is (= [fail-cmd] (vec (:cmd data)))))))

(deftest ^{:stratum 2} test-run!-carries-captured-stderr-into-the-failure
  (testing "given a failing command that wrote to stderr → ex carries the text"
    (let [ex   (is (thrown? clojure.lang.ExceptionInfo
                            (sut/run! {:out :string :err :string}
                                      ;; Assembled by printf rather than written
                                      ;; out, so "r2-said-no" appears nowhere in
                                      ;; the command vector. The message always
                                      ;; contains that vector, so a literal
                                      ;; payload would satisfy the ex-message
                                      ;; assertion below even if stderr were
                                      ;; never appended. Keep it indirect.
                                      shell-cmd "-c"
                                      "printf '%s-%s' r2 said-no >&2; exit 1")))
          data (ex-data ex)]
      (is (= 1 (:exit data)))
      (is (= "r2-said-no" (:err data))
          "stderr belongs in ex-data: it is what the command said went wrong")
      (is (str/includes? (ex-message ex) "r2-said-no")
          "and in the message, which is what a bare handler prints"))))

(deftest ^{:stratum 2} test-run!-truncates-long-stderr-within-the-bound
  (testing "given stderr past the bound → marked, tail kept, total bounded"
    ;; 5000 zeros then a recognisable tail: longer than the bound, and the
    ;; part worth keeping is at the end, where a failing command puts its
    ;; error.
    (let [ex  (is (thrown? clojure.lang.ExceptionInfo
                           (sut/run! {:out :string :err :string}
                                     shell-cmd "-c"
                                     (str "printf '%05000d' 0 >&2; "
                                          "printf 'TAIL-MARKER' >&2; "
                                          "exit 1"))))
          err (:err (ex-data ex))]
      (is (str/starts-with? err sut/truncation-marker)
          "a truncated block has to say so, or it reads as the whole error")
      (is (str/ends-with? err "TAIL-MARKER")
          "the tail is the half worth keeping")
      (is (<= (count err) sut/max-captured-error-chars)
          "the marker counts against the bound rather than adding to it"))))

(deftest ^{:stratum 2} test-run!-omits-stderr-when-there-is-none
  (testing "given a failing command that wrote nothing → no :err key"
    (let [ex   (is (thrown? clojure.lang.ExceptionInfo
                            (sut/run! {:out :string :err :string} fail-cmd)))
          data (ex-data ex)]
      (is (not (contains? data :err))
          "an empty stream is nothing to report, not an empty report")
      (is (not (str/includes? (ex-message ex) "\n"))
          "the message stays one line when there is no stderr to add"))))

(deftest ^{:stratum 2} test-run!-tolerates-uncaptured-stderr
  (testing "given :err inherited rather than captured → no :err key, still throws"
    (let [ex   (is (thrown? clojure.lang.ExceptionInfo
                            (sut/run! {:out :string :err :inherit} fail-cmd)))
          data (ex-data ex)]
      (is (= 1 (:exit data)))
      (is (not (contains? data :err))))))

(deftest ^{:stratum 2} test-run!-returns-result-on-zero-exit
  (testing "given a zero-exit command → returns :exit 0"
    (let [result (sut/run! {:out :string :err :string} ok-cmd)]
      (is (zero? (:exit result))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-proc.core-test)

  :leave-this-here)
