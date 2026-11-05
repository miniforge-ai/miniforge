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
(ns ai.miniforge.cli.workflow-runner.codex-network-access-test
  "[:llm :codex :network-access?] in config.edn: a boolean the config
   commands check, which gives a local run's codex client network access,
   stops a run when it holds anything else, and refuses a governed run
   before its first LLM call."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.workflow-runner :as workflow-runner]
   [ai.miniforge.cli.workflow-runner.context :as sut]
   [ai.miniforge.cli.workflow-runner.gc-hooks :as gc-hooks]
   [ai.miniforge.cli.workflow-runner.provenance :as provenance]
   [ai.miniforge.cli.workflow-runner.setup :as setup]
   [ai.miniforge.config.interface :as config-interface]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} codex-config
  "A user config on the codex backend with `value` under the key."
  [value]
  {:llm {:backend :codex :codex {:network-access? value}}})

(defn- ^{:stratum 0} client-for
  "The client `create-llm-client` makes under user config `cfg`."
  [cfg]
  (with-redefs [config/load-config (constantly cfg)]
    (sut/create-llm-client nil nil true)))

(defn- ^{:stratum 0} refusal
  "The anomaly `f` throws, or nil when it returns."
  [f]
  (try (f) nil
       (catch clojure.lang.ExceptionInfo e (ex-data e))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} config-commands-check-the-key-test
  (testing "true and false are accepted"
    (is (empty? (config/config-problems (codex-config true))))
    (is (empty? (config/config-problems (codex-config false)))))
  (testing "a value that is not a boolean is a wrong-type problem"
    (is (= [{:path [:llm :codex :network-access?] :problem :wrong-type
             :expected :boolean :value "yes"}]
           (config/config-problems (codex-config "yes"))))))

(deftest ^{:stratum 1} the-key-reaches-a-codex-client-test
  (testing "true gives the codex client network access"
    (is (llm/client-network-access? (client-for (codex-config true)))))
  (testing "false or absent leaves it off"
    (is (not (llm/client-network-access? (client-for (codex-config false)))))
    (is (not (llm/client-network-access? (client-for {:llm {:backend :codex}})))))
  (testing "another backend ignores the key"
    (let [client (client-for {:llm {:backend :claude :codex {:network-access? true}}})]
      (is (= :claude (llm/client-backend client)))
      (is (not (llm/client-network-access? client))))))

(deftest ^{:stratum 1} a-non-boolean-value-stops-the-run-test
  (let [data (refusal #(client-for (codex-config "true")))]
    (is (= :invalid-input (:anomaly/type data)))
    (is (str/includes? (:anomaly/message data) "[:llm :codex :network-access?]"))
    (is (str/includes? (:anomaly/message data) "\"true\""))))

(deftest ^{:stratum 1} governed-runs-are-refused-test
  (let [network (client-for (codex-config true))
        plain   (client-for (codex-config false))]
    (testing "the check refuses only a governed run whose client has network access"
      (is (= :anomalies/forbidden
             (:anomaly/category (refusal #(sut/refuse-network-access-when-governed! :governed network)))))
      (is (nil? (sut/refuse-network-access-when-governed! :local network)))
      (is (nil? (sut/refuse-network-access-when-governed! :governed plain)))))
  (testing "a governed spec run is refused before the workflow selection call"
    (let [selections (atom 0)]
      (with-redefs [config-interface/user-config-requires-governed? (constantly false)
                    config/load-config                (constantly (codex-config true))
                    gc-hooks/run-gc-pass-best-effort! (fn [& _] nil)
                    setup/resolve-workflow-interface  (fn [] {})
                    setup/select-workflow-type        (fn [& _] (swap! selections inc) :canonical-sdlc)]
        (is (= :anomalies/forbidden
               (:anomaly/category
                (refusal #(workflow-runner/run-workflow-from-spec!
                           {:spec/title "t"} {:execution-mode :governed :quiet true})))))
        (is (zero? @selections))))))

(deftest ^{:stratum 1} the-run-banner-says-network-access-is-on-test
  (let [notice (messages/t :workflow-runner/codex-network-notice)
        banner (fn [client]
                 (with-out-str
                   (provenance/print-runtime-provenance!
                    false {:source-root "/src" :worktree-path "/wt" :llm-backend client})))]
    (is (str/includes? (banner (client-for (codex-config true))) notice))
    (is (not (str/includes? (banner (client-for (codex-config false))) notice)))))
