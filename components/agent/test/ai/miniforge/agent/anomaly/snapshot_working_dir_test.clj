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

(ns ai.miniforge.agent.anomaly.snapshot-working-dir-test
  "Coverage for `file-artifacts/snapshot-working-dir` after the
   exceptions-as-data migration. The success path returns the working
   tree snapshot map; the failure path returns a `:fault` anomaly that
   the only internal caller (`collect-written-files`) treats as a
   non-fatal nil."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.agent.file-artifacts :as file-artifacts]
            [ai.miniforge.anomaly.interface :as anomaly]))

;------------------------------------------------------------------------------ Happy path

(deftest snapshot-working-dir-success-returns-snapshot
  (testing "exit 0 yields a snapshot map (no anomaly)"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 0
                             :out " M src/changed.clj\n"
                             :err ""})]
      (let [result (file-artifacts/snapshot-working-dir "/tmp/work")]
        (is (not (anomaly/anomaly? result)))
        (is (map? result))
        (is (contains? result :modified))
        (is (= #{"src/changed.clj"} (:modified result)))))))

;------------------------------------------------------------------------------ Failure path

(deftest snapshot-working-dir-non-zero-exit-returns-fault-anomaly
  (testing "non-zero exit yields a :fault anomaly with diagnostics"
    (with-redefs [clojure.java.shell/sh
                  (fn [& _] {:exit 128 :out "" :err "fatal: not a git repo"})]
      (let [result (file-artifacts/snapshot-working-dir "/path/to/wd")]
        (is (anomaly/anomaly? result))
        (is (= :fault (:anomaly/type result)))
        (is (= "Failed to snapshot working directory"
               (:anomaly/message result)))
        (let [data (:anomaly/data result)]
          (is (= "/path/to/wd" (:working-dir data)))
          (is (= 128 (:exit data)))
          (is (= "fatal: not a git repo" (:stderr data))))))))

;------------------------------------------------------------------------------ Caller passthrough

(deftest collect-written-files-treats-anomaly-as-nil
  (testing "collect-written-files returns nil when snapshot returns an anomaly"
    ;; Anomaly path must not throw; the surrounding review phase
    ;; should observe a plain nil and fall through to the next
    ;; artifact strategy.
    (with-redefs [file-artifacts/snapshot-working-dir
                  (fn [_] (anomaly/anomaly :fault
                                           "boom"
                                           {:working-dir "/tmp"
                                            :exit 1
                                            :stderr "x"}))]
      (let [pre {:untracked #{} :modified #{} :deleted #{} :added #{}}]
        (is (nil? (file-artifacts/collect-written-files pre "/tmp")))))))
