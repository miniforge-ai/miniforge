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
(ns ai.miniforge.llm.backend-for-model-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.model-registry :as registry]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} backend-for-model-test
  (testing "a catalog model id resolves to its catalog backend"
    (is (= :claude (llm/backend-for-model "claude-sonnet-4-6")))
    (is (= :codex (llm/backend-for-model "gpt-5.4" nil))))
  (testing "an unlisted id falls back to :codex by default"
    (is (= :codex (llm/backend-for-model "anthropic/claude-sonnet-4-6"))))
  (testing "an unlisted id returns the caller's not-found value"
    (is (nil? (llm/backend-for-model "anthropic/claude-sonnet-4-6" nil)))
    (is (= :opencode (llm/backend-for-model "qwen3:32b" :opencode)))))

(deftest ^{:stratum 0} catalog-backends-are-registered-test
  (testing "every catalog model names a backend create-client accepts"
    (doseq [[model-key {:keys [backend]}] registry/model-registry]
      (is (contains? llm/backends backend)
          (str model-key " names unregistered backend " backend))))
  (testing "a gemini catalog model gets a Gemini API client"
    (is (= :gemini-api (llm/backend-for-model "gemini-2.5-pro" nil)))
    (is (= :gemini-api (llm/client-backend
                        (llm/create-client-for-model "gemini-2.5-flash"))))))
