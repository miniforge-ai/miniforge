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

(ns ai.miniforge.event-stream.event-type-registry-test
  "Tests for event type registry integrity and derived views."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.event-type-registry :as registry]))

;------------------------------------------------------------------------------ Layer 0
;; Registry entry structure

(deftest registry-entries-have-required-keys-test
  (testing "every registry entry has :constructor, :event-type, :json-string, :browser?"
    (doseq [entry registry/event-type-registry]
      (is (string? (:constructor entry))
          (str "Missing :constructor in " entry))
      (is (keyword? (:event-type entry))
          (str "Missing :event-type in " entry))
      (is (string? (:json-string entry))
          (str "Missing :json-string in " entry))
      (is (boolean? (:browser? entry))
          (str "Missing :browser? in " entry)))))

(deftest registry-event-types-are-namespaced-keywords-test
  (testing "every :event-type is a namespaced keyword"
    (doseq [entry registry/event-type-registry]
      (is (namespace (:event-type entry))
          (str ":event-type " (:event-type entry)
               " must be namespaced keyword")))))

(deftest registry-json-strings-match-event-types-test
  (testing "json-string is the keyword serialized (ns/name)"
    (doseq [entry registry/event-type-registry]
      (let [kw (:event-type entry)
            expected (str (namespace kw) "/" (name kw))]
        (is (= expected (:json-string entry))
            (str "Mismatch for " (:constructor entry)
                 ": expected " expected " got " (:json-string entry)))))))

(deftest registry-constructors-are-unique-test
  (testing "no duplicate constructor names"
    (let [names (map :constructor registry/event-type-registry)
          freq (frequencies names)
          dupes (filter #(> (val %) 1) freq)]
      (is (empty? dupes)
          (str "Duplicate constructors: " dupes)))))

(deftest registry-event-types-are-unique-test
  (testing "no duplicate event-type keywords"
    (let [types (map :event-type registry/event-type-registry)
          freq (frequencies types)
          dupes (filter #(> (val %) 1) freq)]
      (is (empty? dupes)
          (str "Duplicate event types: " dupes)))))

;------------------------------------------------------------------------------ Layer 1
;; Derived views consistency

(deftest browser-handled-events-test
  (testing "browser-handled-events matches entries with :browser? true"
    (let [expected (->> registry/event-type-registry
                        (filter :browser?)
                        (mapv :json-string))]
      (is (= expected registry/browser-handled-events))))

  (testing "browser-handled-events is non-empty"
    (is (pos? (count registry/browser-handled-events)))))

(deftest browser-unhandled-events-test
  (testing "browser-unhandled-events matches entries with :browser? false"
    (let [expected (->> registry/event-type-registry
                        (remove :browser?)
                        (mapv :json-string))]
      (is (= expected registry/browser-unhandled-events))))

  (testing "handled + unhandled = total registry"
    (is (= (count registry/event-type-registry)
           (+ (count registry/browser-handled-events)
              (count registry/browser-unhandled-events))))))

(deftest naming-asymmetries-test
  (testing "naming-asymmetries matches entries with :asymmetry? true"
    (let [expected-count (->> registry/event-type-registry
                              (filter :asymmetry?)
                              count)]
      (is (= expected-count (count registry/naming-asymmetries)))))

  (testing "every asymmetry entry has constructor, json-string, and note"
    (doseq [asym registry/naming-asymmetries]
      (is (string? (:constructor asym)))
      (is (string? (:json-string asym)))
      (is (string? (:asymmetry-note asym))))))

;------------------------------------------------------------------------------ Layer 1
;; Audit summary consistency

(deftest audit-summary-test
  (testing "total count matches registry size"
    (is (= (count registry/event-type-registry)
           (:total-server-events registry/audit-summary))))

  (testing "browser handled count matches"
    (is (= (count registry/browser-handled-events)
           (:browser-handled-count registry/audit-summary))))

  (testing "browser unhandled count matches"
    (is (= (count registry/browser-unhandled-events)
           (:browser-unhandled-count registry/audit-summary))))

  (testing "naming asymmetry count matches"
    (is (= (count registry/naming-asymmetries)
           (:naming-asymmetry-count registry/audit-summary))))

  (testing "no string mismatches reported"
    (is (empty? (:string-mismatches registry/audit-summary)))))