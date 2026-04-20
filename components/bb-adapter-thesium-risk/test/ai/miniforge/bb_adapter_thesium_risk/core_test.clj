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

(ns ai.miniforge.bb-adapter-thesium-risk.core-test
  "Layer 0 tests: config resolution + date/key helpers. Layer 1/2
   orchestration is end-to-end over real cargo/R2/FRED; verified via
   risk-dashboard's `bb publish:daily` with stage credentials rather
   than duplicated here."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-adapter-thesium-risk.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Config resolution.

(deftest test-resolve-cfg-applies-defaults
  (testing "given empty cfg → all default keys populated"
    (let [r (sut/resolve-cfg {})]
      (is (= "dist/daily"  (:daily-output r)))
      (is (= "dist/weekly" (:weekly-output r)))
      (is (= "dashboard_snapshot.json" (:snapshot-filename r)))
      (is (some? (:data-plane r)))
      (is (= "thesium-data-plane/Cargo.toml"
             (get-in r [:data-plane :manifest]))))))

(deftest test-resolve-cfg-deep-merges-data-plane
  (testing "given :data-plane override for :binary → others preserved"
    (let [r (sut/resolve-cfg {:data-plane {:binary "custom/dp"}})]
      (is (= "custom/dp"                         (get-in r [:data-plane :binary])))
      (is (= "thesium-data-plane/Cargo.toml"     (get-in r [:data-plane :manifest]))))))

(deftest test-resolve-cfg-resolves-env-vars
  (testing "given env-var names → values pulled from System/getenv"
    (let [r (sut/resolve-cfg
             {:fred-api-key-env "UNLIKELY_TO_EXIST_ENV_VAR_12345"
              :r2-bucket-env    "UNLIKELY_TO_EXIST_ENV_VAR_12346"
              :r2-endpoint-env  "UNLIKELY_TO_EXIST_ENV_VAR_12347"})]
      (is (nil? (:fred-api-key r)))
      (is (nil? (:r2-bucket r)))
      (is (nil? (:r2-endpoint r))))))

;------------------------------------------------------------------------------ Validate

(deftest test-validate-throws-on-missing-fred-key
  (testing "given cfg with nil :fred-api-key → throws"
    (is (thrown? Exception (sut/validate! {:fred-api-key nil}))))
  (testing "given cfg with blank :fred-api-key → throws"
    (is (thrown? Exception (sut/validate! {:fred-api-key "   "})))))

(deftest test-validate-passes-on-nonblank-fred-key
  (testing "given a non-blank key → no throw"
    (is (nil? (sut/validate! {:fred-api-key "abc123"})))))

;------------------------------------------------------------------------------ Date stamping

(deftest test-today-iso-format
  (testing "today-iso returns YYYY-MM-DD"
    (is (re-matches #"\d{4}-\d{2}-\d{2}" (sut/today-iso)))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-adapter-thesium-risk.core-test)

  :leave-this-here)
