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
(ns ai.miniforge.cli.workflow-runner.run-identity-test
  "One run, one id (#2377). The spec runner registers its run id with the
   operator channel, so `mf workflow cancel <id>` reaches only that id.
   The pipeline runs under the id its options carry (#1441); when the
   context handed to it dropped the id, the pipeline minted a second one,
   and its checkpoints and phase events sat under an id that a cancel
   could not reach: the request stayed `proposed` while the run went on."
  (:require
   [ai.miniforge.automation-edge-correlator.interface :as correlator]
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.control :as control]
   [ai.miniforge.cli.workflow-runner.dashboard :as dashboard]
   [ai.miniforge.cli.workflow-runner.display :as display]
   [ai.miniforge.cli.workflow-runner.execution :as execution]
   [ai.miniforge.cli.workflow-runner.execution-mode :as execution-mode]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.paths :as paths]
   [ai.miniforge.cli.workflow-runner.preflight :as preflight]
   [ai.miniforge.cli.workflow-runner.provenance :as provenance]
   [ai.miniforge.cli.workflow-runner.sandbox :as sandbox]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.cli.workflow-runner.spec-kanban :as spec-kanban]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.supervisory-state.interface :as supervisory]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-spec-capturing
  "Run `run-workflow-from-spec!` with its collaborators stubbed and the real
   `create-workflow-context`. Returns {:registered <id registered with the
   operator channel> :pipeline-opts <options the pipeline was run with>}."
  []
  (let [registered (atom nil)
        pipeline-opts (atom nil)
        stream (es/create-event-stream {:sinks []})
        run-pipeline (fn [_workflow _input opts]
                       (reset! pipeline-opts opts)
                       {:execution/status :completed})]
    (with-redefs-fn
      {#'gc-hooks/run-gc-pass-best-effort! (fn [_repo-fn _gc-fn] nil)
       #'gc-hooks/enqueue-workflow-gc-best-effort! (fn [_enqueue-fn _wid] nil)
       #'execution-mode/allowed-mode! (constantly :local)
       #'setup/resolve-workflow-interface (fn [] {:load-workflow (fn [& _] {})
                                                  :run-pipeline run-pipeline})
       #'setup/load-or-create-workflow (fn [_load _type _version] {:workflow/id :quick-fix})
       #'context/create-llm-client (constantly :llm-client)
       #'context/decorate-spec-with-runtime-context (fn [spec _opts] spec)
       #'context/spec->workflow-input (constantly {})
       #'sandbox/infer-repo-url (constantly nil)
       #'sandbox/infer-branch (constantly nil)
       #'execution/create-artifact-store (constantly nil)
       #'es/create-event-stream (fn [& _] stream)
       #'supervisory/attach! (constantly nil)
       #'correlator/attach! (constantly nil)
       #'display/start-progress! (constantly (fn [] nil))
       #'control/register-workflow-control! (fn [workflow-id _control-state _stream]
                                              (reset! registered workflow-id))
       #'control/release-workflow-control! (constantly nil)
       #'dashboard/print-dashboard-status! (constantly nil)
       #'paths/assert-valid-source-root! (constantly nil)
       #'paths/assert-execution-worktree! (constantly nil)
       #'paths/assert-source-dir-alignment! (constantly nil)
       #'provenance/print-runtime-provenance! (constantly nil)
       #'preflight/run-backend-preflight! (constantly nil)
       #'spec-kanban/start-spec! (fn [input _provenance] [nil input])
       #'spec-kanban/finish-spec! (constantly nil)
       #'sut/trigger-meta-loop-after-workflow! (constantly nil)}
      (fn []
        (with-out-str
          (sut/run-workflow-from-spec! {:spec/title "t" :spec/workflow-type :quick-fix}
                                       {:quiet true}))))
    {:registered @registered :pipeline-opts @pipeline-opts}))

(deftest ^{:stratum 0} the-workflow-context-carries-the-run-id-test
  (testing "the context handed to the pipeline names the run it was built for"
    (let [workflow-id (random-uuid)
          ctx (context/create-workflow-context {:callbacks {}
                                                :event-stream (es/create-event-stream {:sinks []})
                                                :workflow-id workflow-id
                                                :workflow-type :quick-fix
                                                :workflow-version "latest"
                                                :quiet true})]
      (is (= workflow-id (:workflow-id ctx))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-spec-run-executes-under-its-registered-id-test
  (testing "the pipeline gets the id the operator channel routes cancels by"
    (let [{:keys [registered pipeline-opts]} (run-spec-capturing)]
      (is (uuid? registered))
      (is (some? pipeline-opts) "the pipeline ran")
      (is (= registered (:workflow-id pipeline-opts))))))
