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

(ns ai.miniforge.schema.interface.validate-anomaly-happy-path-test
  "Happy-path coverage for `schema/validate-anomaly`: valid values pass
   straight through unchanged."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.schema.interface :as schema]))

(def valid-agent
  {:agent/id (random-uuid)
   :agent/role :implementer
   :agent/capabilities #{:code :test}
   :agent/config {:model "claude-sonnet-4"
                  :temperature 0.3}})

(deftest validate-anomaly-returns-value-on-success
  (testing "valid agent returns the value, not an anomaly"
    (let [result (schema/validate-anomaly schema/Agent valid-agent)]
      (is (= valid-agent result))
      (is (not (anomaly/anomaly? result))))))

(deftest validate-anomaly-preserves-identity
  (testing "result is the input value (identity, not a copy)"
    (is (identical? valid-agent
                    (schema/validate-anomaly schema/Agent valid-agent)))))
