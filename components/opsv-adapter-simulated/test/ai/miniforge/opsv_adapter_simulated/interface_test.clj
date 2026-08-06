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
(ns ai.miniforge.opsv-adapter-simulated.interface-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.opsv-adapter-simulated.interface :as sut]
   [ai.miniforge.phase-opsv.interface :as opsv]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private scenario
  {:opsv/candidate-drivers [{:driver :cpu} {:driver :backlog}]
   :opsv/ramp-result
   {:environment-fingerprint {:cluster "staging"}
    :steps [{:step/id "one"}]}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-adapter-replays-scenario-values
  (let [adapter (sut/create-adapter scenario)]
    (testing "discovery is deterministic and implements the public port"
      (is (satisfies? opsv/OPSVAdapter adapter))
      (is (= (:opsv/candidate-drivers scenario)
             (opsv/discover-signals adapter {:services ["catalog"]}))))
    (testing "guarded load replay is stable across calls"
      (is (= (:opsv/ramp-result scenario)
             (opsv/run-guarded-ramp adapter {:experiment-pack/id "pack"})))
      (is (= (opsv/run-guarded-ramp adapter {})
             (opsv/run-guarded-ramp adapter {}))))))
