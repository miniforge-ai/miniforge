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
(ns ai.miniforge.cli.workflow-runner.store-lifecycle-test
  "Artifact-store lifecycle tests for `run-workflow!`.

   `run-workflow!` opens the transit artifact store itself and nothing
   after it returns closes the store, so every exit path — completion
   or a pipeline throw — must release it before leaving. These tests
   drive the real `run-workflow!` with its collaborators stubbed via
   `with-redefs`, recording every store handed to
   `execution/close-artifact-store` (same recording approach as
   `execution_test.clj`'s store-lifecycle harness for
   `execute-with-events`).

   Note: `gc_integration_test.clj` documents that requiring
   `workflow_runner.clj` hangs a test JVM via load-time background
   threads. That predates the rule-210 namespace split; the load is
   side-effect-free now (verified: zero new threads after `require`),
   which is what makes these direct tests possible."
  (:require
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [clojure.test :refer [deftest is testing]]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-recording-closes
  "Run `run-workflow!` with collaborators stubbed and `run-pipeline`
   as the pipeline fn, recording every store handed to
   `execution/close-artifact-store`. The `:dashboard-url` opt keeps the
   event stream nil, so the manifest and shutdown helpers no-op.
   Returns {:result ... :closed [...]} on normal return,
   {:thrown ... :closed [...]} when the call throws."
  [run-pipeline]
  (let [closed (atom [])
        outcome (with-redefs [gc-hooks/run-gc-pass-best-effort! (fn [_repo-fn _gc-fn] nil)
                              gc-hooks/enqueue-workflow-gc-best-effort! (fn [_enqueue-fn _wid] nil)
                              setup/resolve-workflow-interface (fn []
                                                                {:load-workflow (fn [_id _version] {})
                                                                 :run-pipeline run-pipeline})
                              setup/load-and-validate-workflow (fn [_load-fn _id _version] {})
                              setup/create-phase-callbacks (fn [_quiet] {})
                              context/resolve-input (fn [_opts] {})
                              display/print-workflow-header (fn [_id _version _quiet] nil)
                              display/print-result (fn [_result _opts] nil)
                              execution/create-artifact-store (fn [_quiet] ::sentinel-store)
                              execution/close-artifact-store (fn [store]
                                                               (swap! closed conj store)
                                                               nil)
                              execution/execute-workflow-pipeline (fn [pipeline-fn _workflow _input _callbacks _store _stream]
                                                                    (pipeline-fn))]
                  (try+
                    {:result (sut/run-workflow! "wf-store-lifecycle-test"
                                                {:quiet true
                                                 :dashboard-url "http://dashboard.invalid"})}
                    (catch Object thrown
                      {:thrown thrown})))]
    (assoc outcome :closed @closed)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} run-workflow!-closes-store-once-on-success-test
  (testing "completion path releases the artifact store exactly once"
    (let [{:keys [result closed]} (run-recording-closes
                                   (fn [] {:execution/status :completed}))]
      (is (= :completed (:execution/status result)))
      (is (= [::sentinel-store] closed)))))

(deftest ^{:stratum 1} run-workflow!-closes-store-when-pipeline-throws-test
  (testing "a pipeline exception still releases the artifact store before rethrowing"
    (let [{:keys [result thrown closed]} (run-recording-closes
                                          (fn [] (throw (ex-info "pipeline exploded" {}))))]
      (is (nil? result))
      (is (some? thrown))
      (is (= [::sentinel-store] closed)))))
