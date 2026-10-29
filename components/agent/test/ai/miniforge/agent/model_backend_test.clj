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
   [clojure.string :as str]
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

;; A model id reaches a CLI argv, which takes only strings
(deftest ^{:stratum 1} symbol-model-in-config-reaches-the-client-as-a-string-test
  (testing "a bare word an older `config set` stored as a symbol"
    (let [resolved (resolve-with-config
                    (over-shipped-defaults {:llm {:backend :codex :model 'gpt-5.4}})
                    :implementer (llm/create-client {:backend :codex}))]
      (is (= [:codex "gpt-5.4"] (backend+model resolved))))))

(deftest ^{:stratum 1} blank-model-setting-is-unset-test
  (testing "a blank llm.model falls back to the backend default"
    (is (= [:claude "claude-sonnet-4-6"]
           (backend+model (resolve-with-config
                           (over-shipped-defaults {:llm {:backend :claude :model ""}})
                           :implementer (llm/create-client {:backend :claude}))))))
  (testing "a blank tier setting falls back to llm.model"
    (with-redefs [config/load-merged-config
                  (constantly {:llm {:backend :claude :model "claude-haiku-4-5"}
                               :agents {:default-models {:execution "  "}}})]
      (is (= "claude-haiku-4-5" (model/default-model-for-role :implementer))))))

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
  (testing "a catalog model of another backend is skipped; the backend stays"
    (is (= [:claude "claude-opus-4-6"]
           (binding [*err* (java.io.StringWriter.)]
             (backend+model
              (resolve-with-config {:llm {:backend :claude}
                                    :agents {:default-models {:thinking "gpt-5.4"}}}
                                   :planner
                                   (llm/create-client {:backend :claude}))))))))

;; A setting the backend cannot serve never reaches its CLI
(deftest ^{:stratum 1} mismatched-catalog-model-is-skipped-test
  (testing "an older config's anthropic/ llm.model on codex: warned once, codex default"
    (some-> (resolve 'ai.miniforge.agent.model/warned-mismatches) deref (reset! #{}))
    (let [err (java.io.StringWriter.)
          cfg (over-shipped-defaults {:llm {:backend :codex
                                            :model "anthropic/claude-sonnet-4-5"}})
          resolved (binding [*err* err]
                     (doall (for [role [:implementer :reviewer]]
                              (backend+model (resolve-with-config
                                              cfg role (llm/create-client {:backend :codex}))))))]
      (is (= [[:codex nil] [:codex nil]] resolved))
      (is (= 1 (count (re-seq #"Ignoring llm\.model" (str err)))))
      (is (str/includes? (str err) "anthropic/claude-sonnet-4-5"))
      (is (str/includes? (str err) "codex"))))
  (testing "a Claude id on gemini-api is skipped too"
    (is (= [:gemini-api nil]
           (binding [*err* (java.io.StringWriter.)]
             (backend+model (resolve-with-config
                             {:llm {:backend :gemini-api :model "claude-sonnet-4-6"}}
                             :implementer (llm/create-client {:backend :gemini-api})))))))
  (testing "a skipped tier setting falls through to llm.model"
    (is (= "claude-sonnet-4-5"
           (binding [*err* (java.io.StringWriter.)]
             (with-redefs [config/load-merged-config
                           (constantly {:llm {:backend :claude :model "claude-sonnet-4-5"}
                                        :agents {:default-models {:execution "gpt-5.4"}}})]
               (model/default-model-for-role :implementer))))))
  (testing "a model the catalog does not know is used as given"
    (is (= [:codex "gpt-6-preview"]
           (backend+model (resolve-with-config
                           {:llm {:backend :codex :model "gpt-6-preview"}}
                           :implementer (llm/create-client {:backend :codex})))))))
