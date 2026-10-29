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
(ns ai.miniforge.agent.model-backend-test
  "Backend selection for agent roles when the configured default models
   are ids the model catalog does not list (the shipped defaults use
   provider-prefixed opencode ids such as \"anthropic/claude-sonnet-4-6\")."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private shipped-default-models
  "Same shape and ids as :agents :default-models in
   resources/config/default-user-config.edn."
  {:agents {:default-models {:thinking   "anthropic/claude-opus-4-6"
                             :execution  "anthropic/claude-sonnet-4-6"
                             :escalation "anthropic/claude-opus-4-7"}}})

(defn- ^{:stratum 0} resolve-with-config
  "Resolve `role` against `client` with `cfg` as the merged user config."
  [cfg role client]
  (with-redefs [config/load-merged-config (constantly cfg)]
    (model/resolve-llm-client-for-role role client)))

(defn- ^{:stratum 0} backend+model
  "[backend model] of a resolved client."
  [client]
  [(llm/client-backend client) (get-in client [:config :model])])

;------------------------------------------------------------------------------ Layer 1

;; Unlisted model ids keep the configured backend
(deftest ^{:stratum 1} shipped-defaults-keep-opencode-for-every-role-test
  (testing "an opencode client stays on opencode with the role's default model"
    (let [client (llm/create-client {:backend :opencode})]
      (is (= [:opencode "anthropic/claude-opus-4-6"]
             (backend+model (resolve-with-config shipped-default-models
                                                 :planner client))))
      (doseq [role [:implementer :reviewer :tester :releaser]]
        (is (= [:opencode "anthropic/claude-sonnet-4-6"]
               (backend+model (resolve-with-config shipped-default-models
                                                   role client)))
            (str role " must not be moved off the configured backend"))))))

(deftest ^{:stratum 1} unlisted-plain-model-keeps-backend-test
  (testing "a local model the catalog does not list stays on ollama"
    (is (= [:ollama "qwen3:32b"]
           (backend+model
            (resolve-with-config {:agents {:default-models {:execution "qwen3:32b"}}}
                                 :implementer
                                 (llm/create-client {:backend :ollama})))))))

(deftest ^{:stratum 1} prefixed-id-of-current-backend-model-is-unprefixed-test
  (testing "the shipped anthropic/ ids on a claude client use the bare catalog id"
    (let [client (llm/create-client {:backend :claude})]
      (is (= [:claude "claude-sonnet-4-6"]
             (backend+model (resolve-with-config shipped-default-models
                                                 :implementer client))))
      (is (= [:claude "claude-opus-4-6"]
             (backend+model (resolve-with-config shipped-default-models
                                                 :planner client)))))))

;; Catalog-listed models keep their cross-backend routing
(deftest ^{:stratum 1} listed-model-on-other-backend-still-switches-test
  (testing "a catalog model of another backend gets a client on that backend"
    (is (= [:codex "gpt-5.4"]
           (backend+model
            (resolve-with-config {:agents {:default-models {:thinking "gpt-5.4"}}}
                                 :planner
                                 (llm/create-client {:backend :claude})))))))
