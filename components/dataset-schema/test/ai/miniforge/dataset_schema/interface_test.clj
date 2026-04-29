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

(ns ai.miniforge.dataset-schema.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.dataset-schema.interface :as ds]))

(deftest dataset-types-test
  (testing "All N1 §2.2 dataset types present"
    (is (= #{:table :time-series :document-collection :graph :feature-set :report}
           ds/dataset-types))))

(deftest field-types-test
  (testing "All N1 §4.2 field types present"
    (is (= #{:string :int :long :decimal :double :boolean :date :instant :uuid :json :bytes}
           ds/field-types))
    (is (true? (ds/valid-field-type? :string)))
    (is (false? (ds/valid-field-type? :invalid)))))

(deftest type-widening-test
  (testing "N1 §5.2 backward-compatible type widening"
    (is (true? (ds/type-widening-compatible? :int :long)))
    (is (true? (ds/type-widening-compatible? :int :decimal)))
    (is (false? (ds/type-widening-compatible? :long :int)))
    (is (false? (ds/type-widening-compatible? :string :int)))))

(deftest create-schema-test
  (testing "T1: Create schema with valid fields"
    (let [result (ds/create-schema
                  {:schema/name "test_schema"
                   :schema/version "1.0.0"
                   :schema/fields
                   [{:field/name "id" :field/type :string :field/nullable? false}
                    {:field/name "value" :field/type :decimal :field/nullable? true}]})]
      (is (:success? result))
      (is (some? (get-in result [:schema :schema/id])))
      (is (some? (get-in result [:schema :schema/created-at])))))

  (testing "T2: Reject schema with duplicate field names"
    (let [result (ds/create-schema
                  {:schema/name "bad_schema"
                   :schema/version "1.0.0"
                   :schema/fields
                   [{:field/name "id" :field/type :string :field/nullable? false}
                    {:field/name "id" :field/type :int :field/nullable? false}]})]
      (is (not (:success? result)))
      (is (some #(re-find #"Duplicate" %) (:errors result))))))

(deftest validate-schema-test
  (testing "Reject invalid field type"
    (let [result (ds/validate-schema
                  {:schema/id (java.util.UUID/randomUUID)
                   :schema/name "test_schema"
                   :schema/version "1.0.0"
                   :schema/fields
                   [{:field/name "x" :field/type :invalid :field/nullable? false}]})]
      (is (not (:success? result)))))

  (testing "Reject empty fields"
    (let [result (ds/validate-schema
                  {:schema/id (java.util.UUID/randomUUID)
                   :schema/name "test_schema"
                   :schema/version "1.0.0"
                   :schema/fields []})]
      (is (not (:success? result))))))

(deftest schema-evolution-test
  (testing "T3: Backward compatible - add optional field"
    (let [v1 {:schema/version "1.0.0"
              :schema/fields
              [{:field/name "id" :field/type :string :field/nullable? false}
               {:field/name "price" :field/type :decimal :field/nullable? false}]}
          v2 {:schema/version "1.1.0"
              :schema/fields
              [{:field/name "id" :field/type :string :field/nullable? false}
               {:field/name "price" :field/type :decimal :field/nullable? false}
               {:field/name "currency" :field/type :string :field/nullable? true}]}]
      (is (= :backward-compatible (ds/classify-evolution v1 v2)))))

  (testing "Backward compatible - widen type int->long"
    (let [v1 {:schema/fields [{:field/name "count" :field/type :int :field/nullable? false}]}
          v2 {:schema/fields [{:field/name "count" :field/type :long :field/nullable? false}]}]
      (is (= :backward-compatible (ds/classify-evolution v1 v2)))))

  (testing "Backward compatible - relax nullability"
    (let [v1 {:schema/fields [{:field/name "x" :field/type :string :field/nullable? false}]}
          v2 {:schema/fields [{:field/name "x" :field/type :string :field/nullable? true}]}]
      (is (= :backward-compatible (ds/classify-evolution v1 v2)))))

  (testing "T4: Requires migration - add required field"
    (let [v1 {:schema/fields [{:field/name "id" :field/type :string :field/nullable? false}]}
          v2 {:schema/fields [{:field/name "id" :field/type :string :field/nullable? false}
                              {:field/name "new_req" :field/type :string :field/nullable? false}]}]
      (is (= :requires-migration (ds/classify-evolution v1 v2)))))

  (testing "Requires migration - remove field"
    (let [v1 {:schema/fields [{:field/name "a" :field/type :string :field/nullable? false}
                              {:field/name "b" :field/type :string :field/nullable? false}]}
          v2 {:schema/fields [{:field/name "a" :field/type :string :field/nullable? false}]}]
      (is (= :requires-migration (ds/classify-evolution v1 v2)))))

  (testing "Breaking - incompatible type change"
    (let [v1 {:schema/fields [{:field/name "x" :field/type :string :field/nullable? false}]}
          v2 {:schema/fields [{:field/name "x" :field/type :date :field/nullable? false}]}]
      (is (= :breaking (ds/classify-evolution v1 v2)))))

  (testing "Diff schemas"
    (let [v1 {:schema/fields [{:field/name "a" :field/type :string :field/nullable? false}
                              {:field/name "b" :field/type :int :field/nullable? false}]}
          v2 {:schema/fields [{:field/name "a" :field/type :string :field/nullable? true}
                              {:field/name "c" :field/type :string :field/nullable? true}]}
          diff (ds/diff-schemas v1 v2)]
      (is (= ["c"] (:added diff)))
      (is (= ["b"] (:removed diff)))
      (is (= 1 (count (:changed diff)))))))
