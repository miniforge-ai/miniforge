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
(ns ai.miniforge.cli.workflow-runner.run-error-message-test
  "`run-workflow!` reports a thrown anomaly by its message. Slingshot's
   ex-message for a thrown map is \"throw+: \" followed by the whole map."
  (:require
   [ai.miniforge.cli.workflow-runner :as sut]
   [ai.miniforge.cli.workflow-runner.context :as context]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-output
  "Run `run-workflow!` in pretty mode with workflow loading throwing a
   not-found anomaly. Returns {:out stdout :thrown exception-or-nil}."
  []
  (let [out (java.io.StringWriter.)]
    (with-redefs [gc-hooks/run-gc-pass-best-effort! (fn [_repo-fn _gc-fn] nil)
                  context/resolve-input (fn [_opts] {})
                  setup/load-and-validate-workflow
                  (fn [_load-fn _id _version]
                    (response/throw-anomaly! :anomalies/not-found
                                             "Workflow not found: wf-missing"
                                             {:workflow-id :wf-missing}))]
      (binding [*out* out
                *err* (java.io.StringWriter.)]
        (let [thrown (try
                       (sut/run-workflow! :wf-missing {:event-stream (es/create-event-stream {:sinks []})})
                       nil
                       (catch Exception e e))]
          {:out (str out) :thrown thrown})))))

(defn- ^{:stratum 0} spec-run-output
  "Run `run-workflow-from-spec!` with LLM client creation throwing an
   unavailable anomaly. Returns {:out stdout :thrown exception-or-nil}."
  []
  (let [out (java.io.StringWriter.)]
    (with-redefs [gc-hooks/run-gc-pass-best-effort! (fn [_repo-fn _gc-fn] nil)
                  context/create-llm-client
                  (fn [& _]
                    (response/throw-anomaly! :anomalies/unavailable
                                             "No LLM backend available: codex"
                                             {:backend :codex}))]
      (binding [*out* out
                *err* (java.io.StringWriter.)]
        (let [thrown (try
                       (sut/run-workflow-from-spec! {:spec/title "t"} {})
                       nil
                       (catch Exception e e))]
          {:out (str out) :thrown thrown})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} run-error-prints-anomaly-message-test
  (testing "the run-error line carries the anomaly's message, not the raw map"
    (let [{:keys [out thrown]} (run-output)]
      (is (some? thrown) "the failure still propagates to the command")
      (is (str/includes? out "Workflow not found: wf-missing"))
      (is (not (str/includes? out "throw+")))
      (is (not (str/includes? out ":anomaly/category"))))))

(deftest ^{:stratum 1} spec-execution-error-prints-anomaly-message-test
  (testing "the spec-execution-failed line carries the anomaly's message, not the raw map"
    (let [{:keys [out thrown]} (spec-run-output)]
      (is (some? thrown) "the failure still propagates to the command")
      (is (str/includes? out "No LLM backend available: codex"))
      (is (not (str/includes? out "throw+")))
      (is (not (str/includes? out ":anomaly/category"))))))
