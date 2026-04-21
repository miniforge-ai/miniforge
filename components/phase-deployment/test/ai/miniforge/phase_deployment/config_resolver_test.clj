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

(ns ai.miniforge.phase-deployment.config-resolver-test
  (:require
   [ai.miniforge.phase-deployment.config-resolver :as sut]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Config resolver tests

(deftest extract-placeholders-test
  (testing "extracts placeholders from a single template string"
    (is (= #{"db-host" "db-port"}
           (sut/extract-placeholders
            "postgres://${gcp-sm:db-host}:${gcp-sm:db-port}/app"))))

  (testing "returns an empty set when no placeholders are present"
    (is (= #{}
           (sut/extract-placeholders "plain-text")))))

(deftest extract-placeholders-from-map-test
  (testing "walks nested maps and vectors"
    (is (= #{"db-host" "db-port" "api-key"}
           (sut/extract-placeholders-from-map
            {:database {:host "${gcp-sm:db-host}"
                        :port "${gcp-sm:db-port}"}
             :services [{:name "api"
                         :key "${gcp-sm:api-key}"}]
             :replicas 2})))))

(deftest validate-config-test
  (testing "returns a valid result for matching config"
    (is (= {:valid? true}
           (sut/validate-config {:db-host "10.0.0.1" :db-port 5432}
                                [:map
                                 [:db-host :string]
                                 [:db-port :int]]))))

  (testing "returns invalid-with-errors for schema mismatches"
    (let [result (sut/validate-config {:db-host "10.0.0.1" :db-port "5432"}
                                      [:map
                                       [:db-host :string]
                                       [:db-port :int]])]
      (is (false? (:valid? result)))
      (is (map? (:errors result))))))
