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
(ns ai.miniforge.workflow.runner-environment-codex-network-test
  "Codex network access ([:llm :codex :network-access?]) is for local runs.
   Every pipeline run passes `runner-preparation/acquire-environment`, so a
   governed run whose LLM client has it is refused there, before any
   capsule is made or reused: a resumed run, a chain step, a plan run or a
   DAG child task as much as a spec run."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.dag-executor.interface :as dag]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.workflow.runner-environment :as env]
   [ai.miniforge.workflow.runner-preparation :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} codex-client
  "A codex client, with or without network access; it never runs."
  [network-access?]
  (llm/create-client {:backend :codex
                      :network-access? network-access?
                      :exec-fn (fn [& _] {:out "" :err "" :exit 0})}))

(defn- ^{:stratum 0} acquire
  "Call acquire-environment with `opts` while governed execution is not
   required, with the executor lookup stubbed to stop right after the
   checks. Returns the thrown anomaly's category, ::reused for a
   pre-acquired environment, or the stub's ::stop."
  [opts]
  (with-redefs [config/user-config-requires-governed? (constantly false)
                dag/executor-type                     :type
                env/dag-executor-fns                  (fn [] (throw (ex-info "stop" {:anomaly/category ::stop})))]
    (try
      (let [[acquired _] (sut/acquire-environment {:workflow/id "wf-1"} {} (merge {:repo-path "."} opts))]
        (if (nil? acquired) ::reused acquired))
      (catch clojure.lang.ExceptionInfo e
        (get (ex-data e) :anomaly/category ::no-category)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} governed-runs-with-codex-network-access-are-refused-test
  (testing "a governed run whose client has network access is refused before acquisition"
    (is (= :anomalies/forbidden (acquire {:execution-mode :governed
                                          :llm-backend (codex-client true)}))))
  (testing "a pre-acquired capsule does not exempt it"
    (is (= :anomalies/forbidden (acquire {:execution-mode :governed
                                          :executor {:type :docker}
                                          :environment-id "e"
                                          :llm-backend (codex-client true)}))))
  (testing "the refusal names the config key"
    (let [data (try (env/refuse-network-access-when-governed! "wf-1" :governed true)
                    nil
                    (catch clojure.lang.ExceptionInfo e (ex-data e)))]
      (is (= [:llm :codex :network-access?] (:config/key data)))
      (is (re-find #"network-access\?" (get data :anomaly/message ""))))))

(deftest ^{:stratum 1} other-runs-pass-the-check-test
  (testing "a governed run without network access goes on to acquire its capsule"
    (is (= ::stop (acquire {:execution-mode :governed :llm-backend (codex-client false)})))
    (is (= ::stop (acquire {:execution-mode :governed :llm-backend {:mock :client}}))
        "a mock backend that is not an LLMClient has no network access"))
  (testing "a local run with network access goes on exactly as one without it"
    (doseq [mode [nil :local]]
      (let [with-network (acquire {:execution-mode mode :llm-backend (codex-client true)})]
        (is (not= :anomalies/forbidden with-network) (pr-str mode))
        (is (= (acquire {:execution-mode mode :llm-backend (codex-client false)}) with-network)
            (pr-str mode))))))
