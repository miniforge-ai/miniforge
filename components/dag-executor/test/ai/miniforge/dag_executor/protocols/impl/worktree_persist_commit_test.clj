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
(ns ai.miniforge.dag-executor.protocols.impl.worktree-persist-commit-test
  "The persist commit runs with signing off: a scratch-worktree commit
   must never depend on the operator's signing agent."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dag-executor.protocols.impl.worktree :as wt]
            [ai.miniforge.dag-executor.result :as result]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} persist-commit-disables-signing
  (let [calls (atom [])]
    (with-redefs [wt/run-git (fn [& args] (swap! calls conj (vec args)) {:exit 0 :out "" :err ""})]
      (testing "the commit carries -c commit.gpgsign=false ahead of the subcommand"
        (is (result/ok? (#'wt/commit-staged! "/tmp/wt" "implement phase completed")))
        (let [args (first @calls)
              i (.indexOf ^java.util.List args "commit")]
          (is (= ["-c" "commit.gpgsign=false"] (subvec args (- i 2) i)))
          (is (some #{"--no-verify"} args)))))))

(deftest ^{:stratum 0} persist-commit-failure-is-a-result
  (with-redefs [wt/run-git (fn [& _] {:exit 128 :out "" :err "error: 1Password: failed to fill whole buffer"})]
    (let [r (#'wt/commit-staged! "/tmp/wt" "m")]
      (is (result/err? r))
      (is (= :archive-commit-failed (get-in r [:error :code]))))))
