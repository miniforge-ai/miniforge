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

(ns ai.miniforge.cli.workflow-recommendation-config-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-recommendation-config :as cfg]))

(defn- read-resource-section
  [resource-path]
  (-> resource-path
      io/resource
      slurp
      edn/read-string
      :workflow-recommendation/prompt))

(deftest recommendation-prompt-config-test
  (testing "software-factory prompt config loads from resources"
    (let [config (cfg/recommendation-prompt-config)
          prompt-resource (read-resource-section "config/workflow/recommendation-prompt.edn")]
      (is (= (last (:analysis-dimensions prompt-resource))
             (last (:analysis-dimensions config))))
      (is (= (get-in prompt-resource [:summary-labels :has-review])
             (get-in config [:summary-labels :has-review])))
      (is (= (get-in prompt-resource [:summary-labels :has-testing])
             (get-in config [:summary-labels :has-testing]))))))

(deftest default-prompt-config-loads-from-resource-test
  (testing "fallback recommendation prompt config is resource-backed"
    (let [default-config (cfg/default-prompt-config)
          default-resource (read-resource-section "config/workflow/recommendation-prompt-default.edn")]
      (is (= default-resource default-config)))))
