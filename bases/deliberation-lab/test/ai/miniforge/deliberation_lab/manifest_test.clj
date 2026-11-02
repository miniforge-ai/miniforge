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
(ns ai.miniforge.deliberation-lab.manifest-test
  (:require
   [ai.miniforge.deliberation-lab.manifest :as manifest]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private valid
  {:schema-version 1 :mode :development :model "test-model"
   :conditions [:workspace :workspace-ablated] :replicates 1 :max-input-bytes 48000 :adapter {:call-cap-usd 0.2}
   :price-table {:input-per-million 3 :output-per-million 15}
   :tiers [{:id :tiny :activations 4 :max-normalized-usd 1 :wall-seconds 30}]})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-valid-manifest-has-no-defects
  (is (empty? (manifest/defects valid))))

(deftest ^{:stratum 1} each-defect-is-named
  (testing "the gate mode is refused: development runs never claim G0"
    (is (= [:mode] (manifest/defects (assoc valid :mode :G0)))))
  (is (= [:replicates] (manifest/defects (assoc valid :replicates 0))))
  (is (= [:conditions] (manifest/defects (assoc valid :conditions [:workspace :workspace]))))
  (is (= [:conditions] (manifest/defects (assoc valid :conditions [:c99]))))
  (testing "a tier needs a working turn plus the closing turn"
    (is (= [:tiers] (manifest/defects (assoc-in valid [:tiers 0 :activations] 1)))))
  (is (= [:price-table] (manifest/defects (assoc-in valid [:price-table :output-per-million] -1))))
  (testing "a wrongly shaped field is a named defect, not an exception"
    (is (= [:conditions] (manifest/defects (assoc valid :conditions 42))))
    (is (= [:tiers] (manifest/defects (assoc valid :tiers 42))))
    (is (= [:tiers] (manifest/defects (assoc valid :tiers [42]))))
    (is (= [:adapter] (manifest/defects (dissoc valid :adapter))))
    (is (some #{:manifest} (manifest/defects [])))))
