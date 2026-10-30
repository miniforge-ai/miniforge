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
(ns ai.miniforge.config.execution-test
  "[:execution :require-governed?] fails closed: only false or an absent
   value leaves local execution allowed."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.config.interface :as sut]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-value
  "A user config with `v` at [:execution :require-governed?]."
  [v]
  {:execution {:require-governed? v}})

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} governed-required-fails-closed-test
  (testing "false, nil or no :execution section allows local runs"
    (is (false? (sut/governed-required? {})))
    (is (false? (sut/governed-required? (with-value nil))))
    (is (false? (sut/governed-required? (with-value false)))))
  (testing "true, and any malformed value, requires governed runs"
    (doseq [v [true "true" "yes" "false" 1 :yes]]
      (is (true? (sut/governed-required? (with-value v))) (pr-str v)))))
