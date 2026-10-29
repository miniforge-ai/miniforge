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
(ns ai.miniforge.cli.config-schema-test
  "The config schema's problem report: unknown keys by full path, wrong
   types, unknown backends, sub-keys under :map keys, nil."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.config-schema :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} problems-test
  (let [backends #{:claude :opencode}
        check    #(sut/problems sut/schema backends %)]
    (testing "known keys with values of their type"
      (is (= [] (check {:llm {:backend :claude :model "m"} :dashboard {:port 7878}}))))
    (testing "nil clears any key"
      (is (= [] (check {:llm {:model nil} :dashboard {:port nil}}))))
    (testing "any sub-key below a :map key"
      (is (= [] (check {:self-healing {:agent/per-backend-gap-thresholds {:opencode 210000}}}))))
    (testing "an unknown key is reported by its full path"
      (is (= [{:path [:made :up :key] :problem :unknown-key}] (check {:made {:up {:key 5}}}))))
    (testing "wrong types, an unknown backend, a port out of range, a section set to a value"
      (is (= #{[:dashboard :port] [:llm :backend] [:dashboard :auto-open] [:observability]}
             (set (map :path (check {:dashboard {:port 70000 :auto-open "yes"}
                                     :llm {:backend :bogus}
                                     :observability 5}))))))))

(def ^{:stratum 0} ^:private reader-paths
  "User-config reads that are not a literal get-in / assoc-in / update-in
   path on the loaded config, which config-schema-source-scan-test cannot see: a
   path held in a def, a sub-map read by keyword, a single-key get."
  {[:workflow/worktree-root]             "dag-executor/.../worktree.clj (get config ...)"
   [:policy-packs :extra-search-paths]   "phase/agent_behavior.clj, on (get cfg :policy-packs)"
   [:policy-packs :disabled-pack-ids]    "phase/agent_behavior.clj, on (get cfg :policy-packs)"
   [:policy-pack :trust-roots]           "policy-pack/trust_roots_config.clj user-config-path"
   [:agents :default-models :thinking]   "agent/model.clj, [:agents :default-models tier]"
   [:agents :default-models :execution]  "agent/model.clj, [:agents :default-models tier]"
   [:governance :readiness]              "config/governance.clj, [:governance config-key]"
   [:governance :risk]                   "config/governance.clj, [:governance config-key]"
   [:governance :tiers]                  "config/governance.clj, [:governance config-key]"
   [:governance :knowledge-safety]       "config/governance.clj, [:governance config-key]"
   [:observability :fleet :url]          "event-stream and logging fleet sinks, from opts"
   [:observability :fleet :api-key]      "event-stream and logging fleet sinks, from opts"
   [:observability :fleet :batch-size]   "event-stream and logging fleet sinks, from opts"
   [:observability :fleet :flush-interval-ms] "event-stream and logging fleet sinks, from opts"
   [:observability :fleet :timeout-ms]   "event-stream and logging fleet sinks, from opts"})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} schema-covers-every-non-literal-read-test
  (testing "each read the scan cannot see has a type in the schema"
    (doseq [[path reader] reader-paths]
      (is (some? (sut/key-type sut/schema path)) (str path " read by " reader))))
  (testing "values the fleet commands, the trust-root loader and tenancy store are accepted"
    (is (= [] (sut/problems sut/schema #{:claude}
                            {:fleet {:repos ["acme/service"]}
                             :policy-pack {:trust-roots {:trust-roots/publishers []}}
                             :tenancy {:operator-name "operator1"}
                             :observability {:fleet {:timeout-ms 30000}}})))))
