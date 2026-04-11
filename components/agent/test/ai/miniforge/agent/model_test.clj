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

(ns ai.miniforge.agent.model-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.agent.model :as model]
   [ai.miniforge.config.interface :as config]
   [ai.miniforge.llm.interface :as llm]))

;------------------------------------------------------------------------------ Layer 0
;; Default model policy

(deftest default-model-for-role-test
  (testing "thinking roles default to GPT thinking model"
    (with-redefs [config/load-merged-config
                  (fn []
                    {:agents {:default-models {:thinking "gpt-5.4"
                                               :execution "gpt-5.2-codex"}}})]
      (is (= "gpt-5.4" (model/default-model-for-role :planner)))))

  (testing "execution roles default to GPT execution model"
    (with-redefs [config/load-merged-config
                  (fn []
                    {:agents {:default-models {:thinking "gpt-5.4"
                                               :execution "gpt-5.2-codex"}}})]
      (is (= "gpt-5.2-codex" (model/default-model-for-role :implementer)))
      (is (= "gpt-5.2-codex" (model/default-model-for-role :reviewer))))))

;------------------------------------------------------------------------------ Layer 1
;; Backend resolution

(deftest resolve-llm-client-for-role-test
  (testing "returns same-backend client with model injected"
    (let [client (llm/create-client {:backend :codex})]
      (with-redefs [config/load-merged-config
                    (fn []
                      {:agents {:default-models {:thinking "gpt-5.4"
                                                 :execution "gpt-5.2-codex"}}})]
        (let [resolved (model/resolve-llm-client-for-role :implementer client)]
          (is (= :codex (llm/client-backend resolved)))))))

  (testing "creates a role-specific client when execution model needs a different backend"
    (let [shared-client (llm/create-client {:backend :claude})
          resolved (with-redefs [config/load-merged-config
                                 (fn []
                                   {:agents {:default-models {:thinking "gpt-5.4"
                                                              :execution "gpt-5.2-codex"}}})]
                     (model/resolve-llm-client-for-role :implementer shared-client))]
      (is (= :codex (llm/client-backend resolved)))))

  (testing "returns nil when no shared client is available"
    (let [resolved (with-redefs [config/load-merged-config
                                 (fn []
                                   {:agents {:default-models {:thinking "gpt-5.4"
                                                              :execution "gpt-5.2-codex"}}})]
                     (model/resolve-llm-client-for-role :implementer nil))]
      (is (nil? resolved)))))
