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

(ns ai.miniforge.workspace.core-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.workspace.interface :as sut]))

(deftest resolves-worktree-path-from-ctx
  (testing "returns the configured worktree path when present"
    (is (= "/wt/a" (sut/resolve-execution-workdir {:execution/worktree-path "/wt/a"})))
    (is (= "/wt/b" (sut/resolve-execution-workdir {:worktree-path "/wt/b"})))
    (is (= "/wt/c" (sut/resolve-execution-workdir {:execution/opts {:worktree-path "/wt/c"}}))))
  (testing "priority: :execution/worktree-path wins over the others"
    (is (= "/win" (sut/resolve-execution-workdir
                   {:execution/worktree-path "/win" :worktree-path "/lose"})))))

(deftest governed-run-fails-closed-when-absent
  (testing "no worktree path + governed mode → throws typed anomaly, never CWD"
    (let [ex (try (sut/resolve-execution-workdir {:execution/mode :governed} "implement")
                  nil
                  (catch clojure.lang.ExceptionInfo e e))]
      (is (some? ex) "governed run must fail closed, not fall back to CWD")
      (is (= :anomalies/workdir-unresolved (:anomaly/category (ex-data ex))))
      (is (= "implement" (:workspace/site (ex-data ex))))))
  (testing "governed mode via :execution/opts :execution-mode also fails closed"
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/resolve-execution-workdir {:execution/opts {:execution-mode :governed}})))))

(deftest governed-run-can-return-workdir-anomaly
  (testing "result API returns anomaly data instead of throwing"
    (let [result (sut/resolve-execution-workdir-result {:execution/mode :governed} "verify")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= :anomalies/workdir-unresolved (:anomaly/subtype result)))
      (is (= :anomalies/workdir-unresolved
             (get-in result [:anomaly/data :anomaly/category])))
      (is (= "verify" (get-in result [:anomaly/data :workspace/site]))))))

(deftest local-run-falls-back-to-cwd
  (testing "no worktree path + local (default) mode → process CWD"
    (is (= (System/getProperty "user.dir")
           (sut/resolve-execution-workdir {})))
    (is (= (System/getProperty "user.dir")
           (sut/resolve-execution-workdir {:execution/mode :local}))))
  (testing "blank worktree path is treated as absent"
    (is (= (System/getProperty "user.dir")
           (sut/resolve-execution-workdir {:execution/worktree-path "   "})))))
