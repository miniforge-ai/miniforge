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
  "Backend and model selection for agent roles: the backend never changes,
   and the model follows the backend unless the user config names one."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private shipped-default-models
  "User config naming opencode-style provider-prefixed models, as the
   shipped defaults did before they stopped naming any."
  {:agents {:default-models {:thinking   "anthropic/claude-opus-4-6"
                             :execution  "anthropic/claude-sonnet-4-6"
                             :escalation "anthropic/claude-opus-4-7"}}})

(defn- ^{:stratum 0} over-shipped-defaults
  "`user-cfg` layered over the shipped default config the way
   merge-configs layers them, without reading environment variables."
  [user-cfg]
  (merge-with merge config/default-config user-cfg))

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

;; The shipped defaults name no model, so each backend gets its own
(deftest ^{:stratum 1} shipped-defaults-follow-the-backend-test
  (let [shipped (over-shipped-defaults {})]
    (testing "codex gets no model from miniforge: the codex CLI uses its own"
      (doseq [role [:planner :implementer :reviewer]]
        (is (= [:codex nil]
               (backend+model (resolve-with-config
                               shipped role (llm/create-client {:backend :codex}))))
            (str role " must not get a Claude model id on codex"))))
    (testing "anthropic-api gets the bare Claude ids the Messages API takes"
      (let [client (llm/create-client {:backend :anthropic-api})]
        (is (= [:anthropic-api "claude-opus-4-6"]
               (backend+model (resolve-with-config shipped :planner client))))
        (is (= [:anthropic-api "claude-sonnet-4-6"]
               (backend+model (resolve-with-config shipped :implementer client))))))
    (testing "opencode keeps its provider-prefixed ids"
      (is (= [:opencode "anthropic/claude-sonnet-4-6"]
             (backend+model (resolve-with-config
                             shipped :implementer (llm/create-client {:backend :opencode}))))))))

;; An explicit model is the user's choice over any default
(deftest ^{:stratum 1} llm-model-beats-the-defaults-test
  (testing "llm.model (MINIFORGE_LLM_MODEL) sets every role's model"
    (doseq [[backend llm-model] [[:claude "claude-sonnet-4-5"] [:ollama "qwen3:32b"]]
            :let [cfg (over-shipped-defaults {:llm {:backend backend :model llm-model}})]
            role [:planner :implementer :tester]]
      (is (= llm-model (with-redefs [config/load-merged-config (constantly cfg)]
                         (model/default-model-for-role role)))
          (str role " on " backend " must use the configured llm.model"))))
  (testing "an anthropic/ llm.model reaches the claude CLI as the bare id"
    ;; `mf config set|init|backend` used to write this value into new files.
    (is (= [:claude "claude-sonnet-4-5"]
           (backend+model
            (resolve-with-config
             (over-shipped-defaults {:llm {:model "anthropic/claude-sonnet-4-5"}})
             :implementer (llm/create-client {:backend :claude}))))))
  (testing "a role tier setting beats llm.model for that tier only"
    (let [cfg (over-shipped-defaults {:llm {:model "gpt-5.4"}
                                      :agents {:default-models {:thinking "gpt-5.4-pro"}}})]
      (with-redefs [config/load-merged-config (constantly cfg)]
        (is (= "gpt-5.4-pro" (model/default-model-for-role :planner)))
        (is (= "gpt-5.4" (model/default-model-for-role :implementer)))))))

;; Selecting a model never changes the backend
(deftest ^{:stratum 1} catalog-model-keeps-the-configured-backend-test
  (testing "a Claude catalog model keeps an anthropic-api client on the API"
    (let [client   (llm/create-client {:backend :anthropic-api :api-key "test-key"})
          resolved (resolve-with-config
                    {:agents {:default-models {:execution "claude-sonnet-4-6"}}}
                    :implementer client)]
      (is (= [:anthropic-api "claude-sonnet-4-6"] (backend+model resolved)))
      (is (= "test-key" (get-in resolved [:config :api-key]))
          "the client keeps the rest of its config")))
  (testing "a catalog model of another backend stays on the configured backend"
    (is (= [:claude "gpt-5.4"]
           (backend+model
            (resolve-with-config {:agents {:default-models {:thinking "gpt-5.4"}}}
                                 :planner
                                 (llm/create-client {:backend :claude})))))))
