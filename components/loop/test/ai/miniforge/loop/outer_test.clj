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

(ns ai.miniforge.loop.outer-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.loop.outer :as outer]))

(def test-spec
  {:spec/id (random-uuid)
   :description "Exercise outer-loop phase transitions"})

(deftest valid-phase-transition-test
  (testing "advance and rollback transitions are explicit"
    (is (outer/valid-phase-transition? :spec :loop/advance))
    (is (outer/valid-phase-transition? :review :loop/advance))
    (is (outer/valid-phase-transition? :review :loop/rollback-to-plan)))

  (testing "forbidden transitions are rejected"
    (is (not (outer/valid-phase-transition? :spec :loop/rollback-to-plan)))
    (is (not (outer/valid-phase-transition? :observe :loop/advance)))
    (is (not (outer/valid-phase-transition? :plan :loop/rollback-to-review)))))

(deftest phase-definition-test
  (testing "phase definitions expose localized descriptions"
    (is (= "Specification received and validated"
           (:phase/description (outer/get-phase-definition :spec))))
    (is (= "Monitor deployment and collect telemetry"
           (:phase/description (outer/get-phase-definition :observe))))))

(deftest advance-phase-test
  (testing "advancing updates the authoritative phase and history"
    (let [loop-state (outer/create-outer-loop test-spec {})
          advanced (outer/advance-phase loop-state {})]
      (is (= :plan (outer/get-current-phase advanced)))
      (is (= :plan (:loop/phase advanced)))
      (is (= [:entered :completed :entered]
             (mapv :outcome (:loop/history advanced))))))

  (testing "advancing from the terminal phase is forbidden"
    (let [loop-state (nth (iterate #(outer/advance-phase % {}) (outer/create-outer-loop test-spec {}))
                          (dec (count outer/phases)))]
      (is (= :observe (outer/get-current-phase loop-state)))
      (is (nil? (outer/advance-phase loop-state {}))))))

(deftest rollback-phase-test
  (testing "rollback to an earlier phase is allowed"
    (let [loop-state (nth (iterate #(outer/advance-phase % {}) (outer/create-outer-loop test-spec {}))
                          3)
          rolled-back (outer/rollback-phase loop-state :plan {})]
      (is (= :implement (outer/get-current-phase loop-state)))
      (is (= :plan (outer/get-current-phase rolled-back)))
      (is (= :rolled-back (:outcome (nth (:loop/history rolled-back) 7))))))

  (testing "rollback to a later phase is rejected"
    (let [loop-state (outer/advance-phase (outer/create-outer-loop test-spec {}) {})]
      (is (= :plan (outer/get-current-phase loop-state)))
      (is (nil? (outer/rollback-phase loop-state :review {}))))))
