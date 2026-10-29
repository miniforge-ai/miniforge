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
(ns ai.miniforge.release-executor.shell-test
  "Tests for the deadline-bounded runner used by host-mode fetch, push and gh."
  (:require
   [ai.miniforge.release-executor.shell :as shell]
   [babashka.fs :as fs]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} pid-alive?
  "True while process `pid` exists and has not exited."
  [pid]
  (let [h (java.lang.ProcessHandle/of (long pid))]
    (and (.isPresent h) (.isAlive ^java.lang.ProcessHandle (.get h)))))

(deftest ^{:stratum 0} finished-command-test
  (testing "a command that exits returns its exit code and output"
    (let [r (shell/run-bounded ["sh" "-c" "echo out; echo err >&2; exit 3"] {})]
      (is (= 3 (:exit r)))
      (is (= "out\n" (:out r)))
      (is (= "err\n" (:err r)))
      (is (not (:timed-out? r)))))
  (testing "git and gh prompts are disabled; caller env is merged on top"
    (let [r (shell/run-bounded
             ["sh" "-c" "echo $GIT_TERMINAL_PROMPT $GH_PROMPT_DISABLED $EXTRA"]
             {:extra-env {"EXTRA" "x"}})]
      (is (= "0 1 x" (str/trim (:out r))))))
  (testing ":dir sets the working directory"
    (let [dir (fs/create-temp-dir {:prefix "shell-dir-"})]
      (try
        (is (= (str (fs/canonicalize dir))
               (str/trim (:out (shell/run-bounded ["pwd" "-P"] {:dir dir})))))
        (finally (fs/delete-tree dir))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} wait-until-dead
  "Poll up to `ms` for `pid` to exit. Returns true when it has."
  [pid ms]
  (loop [left ms]
    (cond
      (not (pid-alive? pid)) true
      (neg? left)            false
      :else (do (Thread/sleep 50) (recur (- left 50))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} hung-command-is-killed-test
  (testing "a command still running at the deadline is killed with its children
            and reported as a :timeout anomaly"
    (let [dir       (fs/create-temp-dir {:prefix "shell-hang-"})
          child-pid (str (fs/path dir "child.pid"))
          started   (System/currentTimeMillis)]
      (try
        (let [r (shell/run-bounded
                 ["sh" "-c" (str "sleep 30 & echo $! > " child-pid "; wait")]
                 {:timeout-ms 500})
              elapsed (- (System/currentTimeMillis) started)]
          (is (:timed-out? r))
          (is (= shell/timeout-exit-code (:exit r)))
          (is (str/includes? (:err r) "sh -c"))
          (is (= :timeout (get-in r [:anomaly :anomaly/type])))
          (is (= 500 (get-in r [:anomaly :anomaly/data :timeout-ms])))
          (is (< elapsed 10000) "returns soon after the deadline, not after the sleep")
          (is (wait-until-dead (parse-long (str/trim (slurp child-pid))) 3000)
              "the grandchild sleep is killed, not orphaned"))
        (finally (fs/delete-tree dir))))))
