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
(ns ai.miniforge.malli-types-test
  "clj-kondo types are derived from declared function schemas, and the
   gate interface is found as a namespace that declares them."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.malli-types :as malli-types]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} gate-name?
  "Declares a keyword argument and a boolean return."
  {:malli/schema [:=> [:cat :keyword] :boolean]}
  [k]
  (keyword? k))

(deftest ^{:stratum 0} schema-namespaces-finds-declaring-sources-test
  (testing "brick sources that declare function schemas are found; test trees are not scanned"
    (let [nss (set (malli-types/schema-namespaces))]
      (is (contains? nss 'ai.miniforge.gate.interface))
      (is (not (contains? nss 'ai.miniforge.malli-types-test))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} kondo-config-carries-declared-types-test
  (testing "a :malli/schema becomes a clj-kondo type-mismatch entry for its arity"
    (let [config (malli-types/kondo-config '[ai.miniforge.malli-types-test])]
      (is (= {:args [:keyword] :ret :boolean}
             (get-in config [:linters :type-mismatch :namespaces
                             'ai.miniforge.malli-types-test 'gate-name?
                             :arities 1]))))))
