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

(ns ai.miniforge.cli.workflow-selection-config-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.workflow-selection-config :as cfg]))

(deftest configured-selection-profiles-test
  (testing "software-factory workflow selection profiles load from resources"
    (let [profiles (cfg/configured-selection-profiles)]
      (is (= :canonical-sdlc-v1 (:comprehensive profiles)))
      (is (= :lean-sdlc-v1 (:fast profiles)))
      (is (= :lean-sdlc-v1 (:default profiles))))))

(deftest resolve-selection-profile-test
  (testing "configured profiles resolve to available workflows"
    (let [available-workflows [{:workflow/id :canonical-sdlc-v1
                                :workflow/phases [{:phase/id :explore}
                                                  {:phase/id :plan}
                                                  {:phase/id :implement}
                                                  {:phase/id :verify}
                                                  {:phase/id :review}
                                                  {:phase/id :release}
                                                  {:phase/id :done}]
                                :workflow/config {:max-total-iterations 50}}
                               {:workflow/id :lean-sdlc-v1
                                :workflow/phases [{:phase/id :plan-design}
                                                  {:phase/id :implement-tdd}
                                                  {:phase/id :review}
                                                  {:phase/id :release}
                                                  {:phase/id :observe}]
                                :workflow/config {:max-total-iterations 20}}]]
      (is (= :canonical-sdlc-v1
             (cfg/resolve-selection-profile :comprehensive available-workflows)))
      (is (= :lean-sdlc-v1
             (cfg/resolve-selection-profile :fast available-workflows)))
      (is (= :lean-sdlc-v1
             (cfg/resolve-selection-profile :default available-workflows))))))
