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

(ns ai.miniforge.phase-deployment.defaults-test
  (:require [ai.miniforge.phase-deployment.defaults :as sut]
            [malli.core :as m]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 1
;; Schema shape

(deftest deploy-budget-schema-test
  (testing "DeployBudget accepts a fully populated map"
    (is (m/validate sut/DeployBudget {:tokens 1000
                                      :iterations 3
                                      :time-seconds 600})))
  (testing "DeployBudget accepts a partial map (every key optional)"
    (is (m/validate sut/DeployBudget {})))
  (testing "DeployBudget rejects negative tokens (nat-int? required)"
    (is (not (m/validate sut/DeployBudget {:tokens -1}))))
  (testing "DeployBudget rejects zero iterations (pos-int? required)"
    (is (not (m/validate sut/DeployBudget {:iterations 0})))))

(deftest deploy-phase-defaults-schema-test
  (testing "DeployPhaseDefaults accepts a typical phase config"
    (is (m/validate sut/DeployPhaseDefaults
                    {:agent :provisioner
                     :gates [:provision-validated]
                     :budget {:tokens 1000}
                     :stack-dir "infra"
                     :stack "production"
                     :gcp-project "my-proj"})))
  (testing "DeployPhaseDefaults accepts an empty map (every key optional)"
    (is (m/validate sut/DeployPhaseDefaults {})))
  (testing ":agent may be nil (`:maybe keyword?`)"
    (is (m/validate sut/DeployPhaseDefaults {:agent nil})))
  (testing ":gates must be a vector of keywords"
    (is (not (m/validate sut/DeployPhaseDefaults {:gates ["string-not-keyword"]})))))

(deftest deploy-defaults-config-schema-test
  (testing "DeployDefaultsConfig accepts a config with all three phase keys"
    (is (m/validate sut/DeployDefaultsConfig
                    {:provision {} :deploy {} :validate {}})))
  (testing "DeployDefaultsConfig accepts an empty map (no phases configured)"
    (is (m/validate sut/DeployDefaultsConfig {}))))

;------------------------------------------------------------------------------ Layer 1
;; Public API — phase-defaults

(deftest phase-defaults-returns-empty-for-unknown-phase-test
  (testing "phase-defaults returns {} for an unknown phase keyword"
    (is (= {} (sut/phase-defaults :totally-unknown-phase)))))

(deftest phase-defaults-returns-known-phase-shape-test
  (testing "phase-defaults returns a map for known phases (shape, not specific values).
            The actual values come from the EDN config file shipped with the
            component; this assertion just locks down that the lookup works
            and yields a map (or empty map) rather than throwing."
    (doseq [phase [:provision :deploy :validate]]
      (is (map? (sut/phase-defaults phase))
          (str "phase-defaults should return a map for " phase)))))
