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
  "Every pipeline run passes `runner-preparation/acquire-environment`, so
   the config's [:execution :require-governed?] is enforced there: a run
   that would execute locally (a DAG child task, a resumed run, a chain
   step, or one handed a pre-acquired executor) is refused before any
   worktree or capsule is made or reused."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-preparation :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} acquire
  "Call acquire-environment with `opts` while governed execution is
   required, with the executor lookup stubbed to stop right after the
   check. Returns the thrown anomaly's category, ::reused for a
   pre-acquired environment, or the stub's ::stop."
  [opts]
  (with-redefs [config/user-config-requires-governed? (constantly true)
                env/dag-executor-fns                  (fn [] (throw (ex-info "stop" {:anomaly/category ::stop})))]
    (try
      (let [[acquired _] (sut/acquire-environment {:workflow/id "wf-1"} {} (merge {:repo-path "."} opts))]
        (if (nil? acquired) ::reused acquired))
      (catch clojure.lang.ExceptionInfo e
        (get (ex-data e) :anomaly/category ::no-category)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} local-runs-are-refused-when-governed-is-required-test
  (testing "a run with no mode, or :local, is refused before acquisition"
    (doseq [mode [nil :local]]
      (is (= :anomalies/forbidden (acquire {:execution-mode mode})) (pr-str mode))))
  (testing "a run handed a pre-acquired executor is refused too unless it is governed"
    (is (= :anomalies/forbidden (acquire {:execution-mode :local :executor :x :environment-id "e"})))
    (is (= ::reused (acquire {:execution-mode :governed :executor :x :environment-id "e"}))))
  (testing "a governed run passes the check and goes on to acquire its capsule"
    (is (= ::stop (acquire {:execution-mode :governed})))))
