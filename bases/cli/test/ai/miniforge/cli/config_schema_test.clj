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
  "Every user-config key path the code reads or writes, with its reader.
   Found by grepping src for get-in / assoc-in / update-config! on the
   config loaded from ~/.miniforge/config.edn. A new reader belongs here
   and in config-schema.edn, or config set and validate reject its key."
  {[:llm :backend]                       "agent/model.clj, cli/config.clj"
   [:llm :model]                         "agent/model.clj"
   [:agents :default-models :thinking]   "agent/model.clj"
   [:agents :default-models :execution]  "agent/model.clj"
   [:dashboard :port]                    "cli/main/commands/monitoring.clj"
   [:workflow :checkpoint-root]          "workflow/checkpoint_store_paths.clj"
   [:workflow/worktree-root]             "dag-executor/.../worktree.clj"
   [:policy-packs :extra-search-paths]   "phase/agent_behavior.clj"
   [:policy-packs :disabled-pack-ids]    "phase/agent_behavior.clj"
   [:policy-pack :trust-roots]           "policy-pack/trust_roots_config.clj"
   [:fleet :repos]                       "cli fleet and pr commands, pr-sync, web-dashboard trains"
   [:governance :readiness]              "config/governance.clj"
   [:governance :risk]                   "config/governance.clj"
   [:governance :tiers]                  "config/governance.clj"
   [:governance :knowledge-safety]       "config/governance.clj"
   [:observability :event-sinks]         "event-stream/sinks.clj"
   [:observability :log-sinks]           "logging/sinks.clj"
   [:observability :alerts]              "observer/alert_subscriber.clj"
   [:observability :fleet :url]          "event-stream/sinks.clj, logging/sinks.clj"})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} schema-covers-every-reader-path-test
  (testing "each key the code reads has a type in the schema"
    (doseq [[path reader] reader-paths]
      (is (some? (sut/key-type sut/schema path)) (str path " read by " reader))))
  (testing "values the fleet commands and the trust-root loader store are accepted"
    (is (= [] (sut/problems sut/schema #{:claude}
                            {:fleet {:repos ["acme/service"]}
                             :policy-pack {:trust-roots {:trust-roots/publishers []}}})))))
