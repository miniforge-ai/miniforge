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
(ns ai.miniforge.cli.main.commands.doctor.checks-test
  (:require
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.main.commands.doctor.checks :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} short-timeout-ms 300)

(def ^{:stratum 0} sleep-seconds 5)

(defn- ^{:stratum 0} elapsed-ms
  [start-ns]
  (quot (- (System/nanoTime) start-ns) 1000000))

(deftest ^{:stratum 0} command-outcome-test
  (testing "not on PATH"
    (is (= {:ok? false :detail "gh not found on PATH"}
           (sut/command-outcome "gh" nil))))
  (testing "success reports the first output line, stdout before stderr"
    (is (= {:ok? true :detail "v1"}
           (sut/command-outcome "x" {:exit 0 :out "v1\nmore\n" :err "noise"})))
    (is (= {:ok? true :detail "on stderr"}
           (sut/command-outcome "x" {:exit 0 :out "  \n" :err "on stderr\n"}))))
  (testing "failure reports the exit code and the first output line"
    (is (= {:ok? false :detail "gh exited 1: not logged in"}
           (sut/command-outcome "gh" {:exit 1 :out "" :err "not logged in\nrun gh auth login"})))
    (is (= {:ok? false :detail "git exited 1"}
           (sut/command-outcome "git" {:exit 1 :out "" :err ""})))))

(deftest ^{:stratum 0} finish-check-test
  (let [spec (sut/command-spec :gh-auth "GitHub CLI login" ["gh" "auth" "status"]
                               :ok-detail "logged in"
                               :hint "Log in with: gh auth login")]
    (testing "success shows the fixed ok detail and no hint"
      (let [result (sut/finish-check spec {:ok? true :detail "github.com"})]
        (is (= "logged in" (:check/detail result)))
        (is (= [] (:check/details result)))
        (is (not (contains? result :check/argv)))))
    (testing "failure shows the command's detail and the hint"
      (let [result (sut/finish-check spec {:ok? false :detail "gh exited 1: x"})]
        (is (= "gh exited 1: x" (:check/detail result)))
        (is (= ["Log in with: gh auth login"] (:check/details result)))))))

(deftest ^{:stratum 0} require-output-test
  (let [spec (sut/command-spec :git/user.email "Git user.email" ["git" "config" "user.email"]
                               :require-output? true)]
    (testing "a zero exit with blank output fails when the spec requires output"
      (is (= {:ok? false :detail "set but empty"}
             (sut/require-output spec {:ok? true :detail ""}))))
    (testing "output present, or a spec that does not require it: outcome unchanged"
      (is (= {:ok? true :detail "a@b.c"}
             (sut/require-output spec {:ok? true :detail "a@b.c"})))
      (is (= {:ok? true :detail ""}
             (sut/require-output (dissoc spec :check/require-output?) {:ok? true :detail ""}))))
    (testing "the flag does not leak into the result"
      (is (not (contains? (sut/finish-check spec {:ok? true :detail "a@b.c"})
                          :check/require-output?))))))

(deftest ^{:stratum 0} config-check-test
  (let [dir (fs/create-temp-dir {:prefix "doctor-config-"})
        path (fn [n] (str (fs/path dir n)))]
    (try
      (spit (path "bad.edn") "{:llm {:backend :claude}")
      (spit (path "good.edn") "{:llm {:backend :claude}}")
      (testing "a config.edn that does not parse fails, naming the error, with a hint"
        (let [result (sut/config-check (path "bad.edn"))]
          (is (false? (:check/ok? result)))
          (is (false? (:check/required? result)) "runs still work, on defaults")
          (is (str/includes? (:check/detail result) "does not parse"))
          (is (seq (:check/details result)))))
      (testing "a config.edn that parses passes"
        (is (true? (:check/ok? (sut/config-check (path "good.edn"))))))
      (testing "no config.edn: optional, defaults apply"
        (is (false? (:check/ok? (sut/config-check (path "absent.edn"))))))
      (finally (fs/delete-tree dir)))))

(deftest ^{:stratum 0} unknown-backend-fails-without-running-anything-test
  (let [result (sut/command-check (sut/backend-spec :no-such-backend))]
    (is (true? (:check/required? result)))
    (is (false? (:check/ok? result)))
    (is (str/includes? (:check/detail result) "unknown backend"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} hung-command-fails-within-the-timeout-test
  (testing "a tool that never answers fails its check once the timeout passes"
    (let [bin        (fs/create-temp-dir)
          script     (fs/path bin "slow-tool")
          real-which fs/which
          start      (System/nanoTime)]
      (spit (str script) (str "#!/bin/sh\nexec sleep " sleep-seconds "\n"))
      (fs/set-posix-file-permissions script "rwxr-xr-x")
      (try
        (with-redefs [sut/check-timeout-ms short-timeout-ms
                      fs/which             (fn [program & _]
                                             (real-which program {:paths [(str bin)]}))]
          (let [result (sut/command-check (sut/command-spec :slow "Slow tool" ["slow-tool" "--version"]))]
            (is (false? (:check/ok? result)))
            (is (str/includes? (:check/detail result) "did not finish within 300 ms"))
            (is (< (elapsed-ms start) (* 1000 sleep-seconds)))))
        (finally
          (fs/delete-tree bin))))))
