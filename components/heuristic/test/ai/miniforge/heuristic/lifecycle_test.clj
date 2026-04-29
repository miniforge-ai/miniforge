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

;; Copyright 2025-2026 Christopher Lester. Licensed under Apache 2.0.

(ns ai.miniforge.heuristic.lifecycle-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.heuristic.lifecycle :as lifecycle]))

(deftest statuses-test
  (testing "statuses are loaded from lifecycle config"
    (is (= #{:draft :shadow :canary :active :deprecated}
           lifecycle/statuses))))

(deftest valid-transition-test
  (testing "allows forward lifecycle transitions"
    (is (lifecycle/valid-transition? :draft :shadow))
    (is (lifecycle/valid-transition? :shadow :canary))
    (is (lifecycle/valid-transition? :canary :active))
    (is (lifecycle/valid-transition? :active :deprecated)))

  (testing "allows deprecation from active lifecycle states"
    (is (lifecycle/valid-transition? :draft :deprecated))
    (is (lifecycle/valid-transition? :shadow :deprecated))
    (is (lifecycle/valid-transition? :canary :deprecated)))

  (testing "rejects backward and terminal exits"
    (is (not (lifecycle/valid-transition? :active :canary)))
    (is (not (lifecycle/valid-transition? :shadow :draft)))
    (is (not (lifecycle/valid-transition? :deprecated :active)))
    (is (not (lifecycle/valid-transition? :deprecated :deprecated)))))

(deftest transition-test
  (testing "returns the transitioned status for valid transitions"
    (is (= :shadow (lifecycle/transition :draft :shadow)))
    (is (= :deprecated (lifecycle/transition :canary :deprecated))))

  (testing "returns nil for forbidden transitions"
    (is (nil? (lifecycle/transition :draft :active)))
    (is (nil? (lifecycle/transition :deprecated :active)))))

(deftest status-predicate-test
  (testing "traffic-serving statuses are explicit"
    (is (lifecycle/can-serve-traffic? :canary))
    (is (lifecycle/can-serve-traffic? :active))
    (is (not (lifecycle/can-serve-traffic? :shadow))))

  (testing "promotable statuses stop at active"
    (is (lifecycle/promotable? :draft))
    (is (lifecycle/promotable? :shadow))
    (is (lifecycle/promotable? :canary))
    (is (not (lifecycle/promotable? :active)))
    (is (not (lifecycle/promotable? :deprecated)))))
