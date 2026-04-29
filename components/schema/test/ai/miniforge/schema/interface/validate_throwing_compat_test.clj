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

(ns ai.miniforge.schema.interface.validate-throwing-compat-test
  "Backward-compat coverage for the deprecated throwing `schema/validate`.

   Existing callers depend on the throwing shape; the deprecation does not
   change behavior — `validate` still returns the value on success and
   throws ex-info on failure, but it now delegates to `validate-anomaly`
   under the hood."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.schema.interface :as schema]))

(def valid-agent
  {:agent/id (random-uuid)
   :agent/role :implementer
   :agent/capabilities #{:code :test}
   :agent/config {:model "claude-sonnet-4"
                  :temperature 0.3}})

(def invalid-agent
  {:agent/id "not-a-uuid"
   :agent/role :invalid-role})

(deftest validate-still-returns-value-on-success
  (testing "deprecated throwing variant returns input unchanged on success"
    (is (= valid-agent (schema/validate schema/Agent valid-agent)))))

(deftest validate-still-throws-ex-info-on-failure
  (testing "deprecated throwing variant still throws ExceptionInfo on bad input"
    (is (thrown? clojure.lang.ExceptionInfo
                 (schema/validate schema/Agent invalid-agent)))))

(deftest validate-thrown-ex-data-carries-errors
  (testing "thrown ex-info carries :errors map for legacy callers"
    (try
      (schema/validate schema/Agent invalid-agent)
      (is false "should have thrown")
      (catch clojure.lang.ExceptionInfo e
        (let [data (ex-data e)]
          (is (map? (:errors data)))
          (is (= invalid-agent (:value data))))))))
