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

(ns ai.miniforge.loop.gate-anomaly-shape-test
  "Lock the canonical anomaly shape produced by `gates/fail-result`
   after the W2 anomaly convergence flip.

   Pre-flip the `:gate/anomaly` was constructed via
   `response/gate-anomaly`, which wrote `:anomaly.gate/errors` at the
   anomaly map's top level alongside `:anomaly/category`.

   Post-flip it is built via `anomaly/sub-anomaly` with
   `:anomaly/type :invalid-input` (per the runbook map for
   `:anomalies.gate/validation-failed`), the legacy category preserved
   as `:anomaly/subtype`, and the gate errors carried under
   `:anomaly/data :gate/errors` (the canonical schema is closed —
   domain payload lives in `:anomaly/data`)."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.loop.gates :as gates]))

(def ^:private sample-errors
  [(gates/make-error :syntax-error "Unexpected EOF")
   (gates/make-error :missing-require "core required but not imported")])

(deftest fail-result-emits-canonical-gate-anomaly
  (testing ":gate/anomaly satisfies the canonical anomaly contract"
    (let [result (gates/fail-result :syntax-check :syntax sample-errors)
          anom   (:gate/anomaly result)]
      (is (anomaly/anomaly? anom)
          ":gate/anomaly is a canonical anomaly map after W2 flip")
      (is (= :invalid-input (:anomaly/type anom))
          ":anomalies.gate/validation-failed maps to :invalid-input per the runbook")
      (is (= :anomalies.gate/validation-failed (:anomaly/subtype anom))
          "legacy gate category preserved verbatim as :anomaly/subtype")))

  (testing "gate identity + error payload live under :anomaly/data"
    (let [result (gates/fail-result :syntax-check :syntax sample-errors)
          data   (:anomaly/data (:gate/anomaly result))]
      (is (= :syntax-check (:gate/id data))
          "gate id moves from top-level :anomaly.gate/* into :anomaly/data")
      (is (= :syntax (:gate/type data)))
      (is (= sample-errors (:gate/errors data))
          "gate errors carried under :anomaly/data :gate/errors")))

  (testing "flat :gate/errors on the result map is unchanged"
    (let [result (gates/fail-result :syntax-check :syntax sample-errors)]
      (is (= sample-errors (:gate/errors result))
          "flat :gate/errors on the gate result is independent of the embedded anomaly")
      (is (false? (:gate/passed? result))))))

(deftest fail-result-no-top-level-domain-keys-on-anomaly
  (testing "domain payload does NOT leak onto the anomaly's top level"
    (let [anom (:gate/anomaly (gates/fail-result :syntax-check :syntax sample-errors))]
      (is (nil? (:anomaly.gate/errors anom))
          "legacy :anomaly.gate/errors no longer set at the anomaly's top level")
      (is (nil? (:gate/errors anom))
          ":gate/errors stays under :anomaly/data, not at the anomaly's top level"))))
