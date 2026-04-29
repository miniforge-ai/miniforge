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

(ns ai.miniforge.workflow.configurable-defaults-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.configurable-defaults :as defaults]))

(deftest max-phases-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/max-phases)))))

(deftest default-inner-loop-iterations-test
  (testing "returns a positive integer"
    (is (pos-int? (defaults/default-inner-loop-iterations)))))

(deftest default-phase-metrics-test
  (testing "returns the default metrics map"
    (is (= {:tokens 0
            :cost-usd 0.0
            :duration-ms 0}
           (defaults/default-phase-metrics)))))
