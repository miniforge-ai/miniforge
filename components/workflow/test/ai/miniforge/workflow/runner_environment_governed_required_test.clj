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
(ns ai.miniforge.workflow.runner-environment-governed-required-test
  "Every run path acquires its environment in `acquire-execution-environment!`,
   so the config's [:execution :require-governed?] is enforced there: a run
   that would execute locally (a DAG child task, a resumed run, a chain step)
   is refused before any worktree or capsule is made."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.workflow.runner-environment :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acquire
  "Call acquire-execution-environment! for `mode` under user config `cfg`,
   with the executor lookup stubbed to stop the call right after the check.
   Returns the thrown anomaly's category, or :reached-acquisition."
  [cfg mode]
  (with-redefs [config/load-merged-config (constantly cfg)
                sut/dag-executor-fns      (fn [] (throw (ex-info "stop" {:anomaly/category ::stop})))]
    (try
      (sut/acquire-execution-environment! "wf-1" {:repo-path "." :execution-mode mode})
      (catch clojure.lang.ExceptionInfo e
        (get (ex-data e) :anomaly/category ::no-category)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} local-runs-are-refused-when-governed-is-required-test
  (testing "a run with no mode, or :local, is refused before acquisition"
    (doseq [mode [nil :local]]
      (is (= :anomalies/forbidden (acquire {:execution {:require-governed? true}} mode))
          (pr-str mode))))
  (testing "a governed run passes the check and goes on to acquire its capsule"
    (is (= ::stop (acquire {:execution {:require-governed? true}} :governed)))))
