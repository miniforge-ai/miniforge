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
(ns ai.miniforge.boundary.interface.category-classification-test
  "Category → anomaly-type mapping. Each standard category resolves to
   the expected canonical anomaly type, and the public mapping table
   covers every category in the vocabulary."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.boundary.interface :as boundary]
   [ai.miniforge.response-chain.interface :as chain]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} boom! [] (throw (RuntimeException. "x")))

(deftest ^{:stratum 0} mapping-table-covers-every-category
  (testing "category->anomaly-type maps every standard category"
    (is (= boundary/exception-categories
           (set (keys boundary/category->anomaly-type))))))

(deftest ^{:stratum 0} classify-category-resolves-known-categories
  (testing "classify-category mirrors the mapping table for every standard category"
    (doseq [category boundary/exception-categories]
      (is (= (get boundary/category->anomaly-type category)
             (boundary/classify-category category))
          (str "classify-category should agree with the table for " category)))))

(deftest ^{:stratum 0} classify-category-falls-back-to-fault-for-unknown
  (testing "classify-category returns :fault when the category is outside the standard vocabulary"
    (is (= :fault (boundary/classify-category :no-such-category)))
    (is (= :fault (boundary/classify-category nil)))
    (is (= :fault (boundary/classify-category "string-not-keyword")))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} category->anomaly-type-for-throw
  "Run a throwing function under `category` and return the anomaly
   type recorded on the chain."
  [category]
  (-> (boundary/execute category (chain/create-chain :flow) :op boom!)
      chain/last-anomaly
      :anomaly/type))

(deftest ^{:stratum 1} captured-payload-records-the-supplied-category
  (testing ":boundary/category in the captured payload echoes the call-site category"
    (let [c (boundary/execute :db
                              (chain/create-chain :flow)
                              :op
                              boom!)]
      (is (= :db (-> c chain/last-anomaly :anomaly/data :boundary/category))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} network-maps-to-unavailable
  (is (= :unavailable (category->anomaly-type-for-throw :network))))

(deftest ^{:stratum 2} db-maps-to-fault
  (is (= :fault (category->anomaly-type-for-throw :db))))

(deftest ^{:stratum 2} io-maps-to-fault
  (is (= :fault (category->anomaly-type-for-throw :io))))

(deftest ^{:stratum 2} parse-maps-to-invalid-input
  (is (= :invalid-input (category->anomaly-type-for-throw :parse))))

(deftest ^{:stratum 2} timeout-maps-to-timeout
  (is (= :timeout (category->anomaly-type-for-throw :timeout))))

(deftest ^{:stratum 2} unavailable-maps-to-unavailable
  (is (= :unavailable (category->anomaly-type-for-throw :unavailable))))

(deftest ^{:stratum 2} unknown-maps-to-fault
  (is (= :fault (category->anomaly-type-for-throw :unknown))))
