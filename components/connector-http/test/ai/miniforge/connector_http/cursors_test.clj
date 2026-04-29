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

(ns ai.miniforge.connector-http.cursors-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-http.cursors :as cursors]))

;;------------------------------------------------------------------------------ Layer 0
;; Fixtures

(def ^:private ts-fn
  "Standard timestamp extractor for test records."
  #(or (:updated_at %) (:created_at %)))

(def ^:private resource-def
  {:cursor-type :timestamp-watermark})

;;------------------------------------------------------------------------------ Layer 1
;; Tests

(deftest parse-timestamp-test
  (testing "parses valid ISO-8601 timestamp to Instant"
    (is (some? (cursors/parse-timestamp "2024-01-15T10:00:00Z"))))

  (testing "returns nil for nil input"
    (is (nil? (cursors/parse-timestamp nil))))

  (testing "returns nil for empty string"
    (is (nil? (cursors/parse-timestamp ""))))

  (testing "returns nil for blank string"
    (is (nil? (cursors/parse-timestamp "   ")))))

(deftest after-cursor?-test
  (testing "returns true when cursor is nil (no prior watermark)"
    (is (true? (cursors/after-cursor? ts-fn nil {:updated_at "2024-01-15T10:00:00Z"}))))

  (testing "returns true when record is strictly after cursor timestamp"
    (is (true? (cursors/after-cursor? ts-fn
                                      {:cursor/type  :timestamp-watermark
                                       :cursor/value "2024-01-14T00:00:00Z"}
                                      {:updated_at "2024-01-15T10:00:00Z"}))))

  (testing "returns false when record is before cursor timestamp"
    (is (false? (cursors/after-cursor? ts-fn
                                       {:cursor/type  :timestamp-watermark
                                        :cursor/value "2024-01-16T00:00:00Z"}
                                       {:updated_at "2024-01-15T10:00:00Z"}))))

  (testing "returns false when record equals cursor timestamp (not strictly after)"
    (is (false? (cursors/after-cursor? ts-fn
                                       {:cursor/type  :timestamp-watermark
                                        :cursor/value "2024-01-15T10:00:00Z"}
                                       {:updated_at "2024-01-15T10:00:00Z"}))))

  (testing "uses :created_at when :updated_at is absent"
    (is (true? (cursors/after-cursor? ts-fn
                                      {:cursor/type  :timestamp-watermark
                                       :cursor/value "2024-01-14T00:00:00Z"}
                                      {:created_at "2024-01-15T10:00:00Z"})))))

(deftest last-record-cursor-test
  (testing "returns nil for empty records"
    (is (nil? (cursors/last-record-cursor resource-def []))))

  (testing "returns nil for non-watermark resource cursor-type"
    (is (nil? (cursors/last-record-cursor {:cursor-type :offset}
                                          [{:updated_at "2024-01-15T10:00:00Z"}]))))

  (testing "returns nil for resource with no cursor-type"
    (is (nil? (cursors/last-record-cursor {}
                                          [{:updated_at "2024-01-15T10:00:00Z"}]))))

  (testing "returns cursor from last record :updated_at"
    (let [cursor (cursors/last-record-cursor resource-def
                                             [{:updated_at "2024-01-14T00:00:00Z"}
                                              {:updated_at "2024-01-15T10:00:00Z"}])]
      (is (= :timestamp-watermark (:cursor/type cursor)))
      (is (= "2024-01-15T10:00:00Z" (:cursor/value cursor)))))

  (testing "falls back to :created_at when :updated_at absent"
    (let [cursor (cursors/last-record-cursor resource-def
                                             [{:created_at "2024-01-15T10:00:00Z"}])]
      (is (= "2024-01-15T10:00:00Z" (:cursor/value cursor))))))

(deftest sort-by-timestamp-test
  (testing "sorts records ascending by timestamp"
    (let [records [{:updated_at "2024-01-15T10:00:00Z"}
                   {:updated_at "2024-01-13T08:00:00Z"}
                   {:updated_at "2024-01-14T09:00:00Z"}]
          sorted  (cursors/sort-by-timestamp ts-fn records)]
      (is (= ["2024-01-13T08:00:00Z" "2024-01-14T09:00:00Z" "2024-01-15T10:00:00Z"]
             (mapv :updated_at sorted)))))

  (testing "records with nil timestamps sort first"
    (let [records [{:updated_at "2024-01-15T10:00:00Z"}
                   {:title "no timestamp"}
                   {:updated_at "2024-01-13T00:00:00Z"}]
          sorted  (cursors/sort-by-timestamp ts-fn records)]
      (is (nil? (ts-fn (first sorted))))))

  (testing "empty collection returns empty"
    (is (empty? (cursors/sort-by-timestamp ts-fn [])))))

(deftest max-timestamp-cursor-test
  (testing "returns nil for empty records"
    (is (nil? (cursors/max-timestamp-cursor ts-fn []))))

  (testing "returns nil when no records have timestamps"
    (is (nil? (cursors/max-timestamp-cursor ts-fn [{:title "no ts"}]))))

  (testing "returns cursor with maximum timestamp"
    (let [records [{:updated_at "2024-01-13T08:00:00Z"}
                   {:updated_at "2024-01-15T10:00:00Z"}
                   {:updated_at "2024-01-14T09:00:00Z"}]
          cursor  (cursors/max-timestamp-cursor ts-fn records)]
      (is (= :timestamp-watermark (:cursor/type cursor)))
      (is (= "2024-01-15T10:00:00Z" (:cursor/value cursor)))))

  (testing "skips records without timestamps when finding max"
    (let [records [{:updated_at "2024-01-15T10:00:00Z"}
                   {:title "no ts"}
                   {:updated_at "2024-01-13T00:00:00Z"}]
          cursor  (cursors/max-timestamp-cursor ts-fn records)]
      (is (= "2024-01-15T10:00:00Z" (:cursor/value cursor)))))

  (testing "custom timestamp-fn uses alternate fields"
    (let [submitted-fn #(:submitted_at %)
          records [{:submitted_at "2024-01-15T10:00:00Z"}
                   {:submitted_at "2024-01-13T08:00:00Z"}]
          cursor  (cursors/max-timestamp-cursor submitted-fn records)]
      (is (= "2024-01-15T10:00:00Z" (:cursor/value cursor))))))

(comment
  ;; Run: clj -M:dev:test -n ai.miniforge.connector-http.cursors-test
  )
