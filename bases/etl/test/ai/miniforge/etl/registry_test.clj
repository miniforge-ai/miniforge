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

(ns ai.miniforge.etl.registry-test
  (:require
   [ai.miniforge.etl.registry :as registry]
   [ai.miniforge.pipeline-config.interface :as pc]
   [clojure.test :refer [deftest is testing]]))

(deftest supported-types-is-exhaustive
  (testing "every concrete source/sink connector is registered"
    (is (= [:edgar :excel :file :github :gitlab :http :jira :pipeline-output :sarif]
           (registry/supported-types)))))

(deftest build-registry-instantiates-every-type
  (testing "every supported type resolves to a connector instance through the registry"
    (let [env-types (into {} (map-indexed
                              (fn [i t] [(keyword "conn" (str "c" i)) t])
                              (registry/supported-types)))
          {:keys [connector-refs connectors]} (pc/instantiate-connectors
                                                (registry/build-registry)
                                                env-types)]
      (is (= (count env-types) (count connector-refs))
          "every env ref maps to a uuid")
      (is (= (count env-types) (count connectors))
          "every uuid maps to a connector instance")
      (is (every? some? (vals connectors))
          "no nil instances"))))
