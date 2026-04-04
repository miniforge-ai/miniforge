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

(ns ai.miniforge.phase.loader-test
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.phase.interface :as phase]
   [ai.miniforge.phase.loader :as loader]))

(use-fixtures :each
  (fn [f]
    (phase/reset-phase-loader!)
    (f)
    (phase/reset-phase-loader!)))

(deftest configured-phase-namespaces-test
  (testing "phase implementation namespaces are discovered from composed resources"
    (let [phase-namespaces (set (loader/configured-phase-namespaces))]
      (is (contains? phase-namespaces 'ai.miniforge.phase.explore))
      (is (contains? phase-namespaces 'ai.miniforge.phase.plan))
      (is (contains? phase-namespaces 'ai.miniforge.phase.release)))))

(deftest ensure-phase-implementations-loaded-test
  (testing "loading configured phase implementations registers their phase defaults"
    (phase/ensure-phase-implementations-loaded!)
    (let [phases (phase/list-phases)]
      (is (contains? phases :explore))
      (is (contains? phases :plan))
      (is (contains? phases :implement))
      (is (contains? phases :verify))
      (is (contains? phases :review))
      (is (contains? phases :release))
      (is (contains? phases :done)))))
