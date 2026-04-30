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

(ns ai.miniforge.repo-index.schema-test
  (:require [clojure.test :refer [deftest is testing]]
            [malli.core :as m]
            [ai.miniforge.repo-index.schema :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures

(defn- valid-file-record
  [& {:as overrides}]
  (merge {:path "src/a.clj"
          :blob-sha "abc1234"
          :size 1024
          :lines 42
          :language "clojure"
          :generated? false}
         overrides))

(defn- valid-repo-index
  [& {:as overrides}]
  (merge {:tree-sha    "abc1234"
          :repo-root   "/repo"
          :files       [(valid-file-record)]
          :file-count  1
          :total-lines 42
          :languages   {"clojure" 1}
          :indexed-at  (java.util.Date.)}
         overrides))

;------------------------------------------------------------------------------ Layer 1
;; FileRecord

(deftest file-record-valid-test
  (testing "Fully populated FileRecord validates"
    (is (m/validate sut/FileRecord (valid-file-record)))))

(deftest file-record-allows-nil-language-test
  (testing ":language is :maybe :string — nil is valid"
    (is (m/validate sut/FileRecord (valid-file-record :language nil)))))

(deftest file-record-rejects-empty-path-test
  (testing "An empty :path fails validation (min 1)"
    (is (not (m/validate sut/FileRecord (valid-file-record :path ""))))))

(deftest file-record-rejects-short-blob-sha-test
  (testing ":blob-sha must be at least 7 characters"
    (is (not (m/validate sut/FileRecord (valid-file-record :blob-sha "abc"))))))

(deftest file-record-rejects-non-int-size-test
  (testing ":size must be an int"
    (is (not (m/validate sut/FileRecord (valid-file-record :size "1024"))))))

(deftest file-record-rejects-missing-required-test
  (testing "Missing :generated? fails validation (required key)"
    (is (not (m/validate sut/FileRecord (dissoc (valid-file-record) :generated?))))))

;------------------------------------------------------------------------------ Layer 1
;; RepoIndex

(deftest repo-index-valid-test
  (testing "Fully populated RepoIndex with one file validates"
    (is (m/validate sut/RepoIndex (valid-repo-index)))))

(deftest repo-index-empty-files-vector-valid-test
  (testing "Empty :files vector is allowed"
    (is (m/validate sut/RepoIndex (valid-repo-index :files [])))))

(deftest repo-index-rejects-non-inst-indexed-at-test
  (testing ":indexed-at must satisfy inst?"
    (is (not (m/validate sut/RepoIndex (valid-repo-index :indexed-at "2026-04-30"))))))

(deftest repo-index-rejects-bad-language-map-test
  (testing ":languages must be string → int"
    (is (not (m/validate sut/RepoIndex (valid-repo-index :languages {"clojure" "many"}))))))

;------------------------------------------------------------------------------ Layer 1
;; RepoMapEntry / RepoMapSlice

(deftest repo-map-entry-valid-test
  (testing "RepoMapEntry with a non-nil :lang validates"
    (is (m/validate sut/RepoMapEntry
                    {:path "src/a.clj" :lang "clojure" :lines 10 :size 100}))))

(deftest repo-map-entry-allows-nil-lang-test
  (testing ":lang is :maybe :string — nil is valid"
    (is (m/validate sut/RepoMapEntry
                    {:path "src/a.clj" :lang nil :lines 10 :size 100}))))

(deftest repo-map-slice-valid-test
  (testing "Fully populated RepoMapSlice validates"
    (is (m/validate sut/RepoMapSlice
                    {:tree-sha "abc1234"
                     :entries [{:path "src/a.clj" :lang "clojure" :lines 10 :size 100}]
                     :total-files 1
                     :shown-files 1
                     :truncated? false
                     :token-estimate 50}))))

(deftest repo-map-slice-empty-entries-valid-test
  (testing "Empty :entries vector is allowed"
    (is (m/validate sut/RepoMapSlice
                    {:tree-sha "abc1234"
                     :entries []
                     :total-files 0
                     :shown-files 0
                     :truncated? false
                     :token-estimate 0}))))

;------------------------------------------------------------------------------ Layer 1
;; Snippet / SearchHit

(deftest snippet-valid-test
  (testing "Fully populated Snippet validates"
    (is (m/validate sut/Snippet
                    {:start-line 10 :end-line 12 :text "match"}))))

(deftest snippet-empty-text-valid-test
  (testing "Snippet text may be the empty string (min 0)"
    (is (m/validate sut/Snippet
                    {:start-line 0 :end-line 0 :text ""}))))

(deftest search-hit-valid-test
  (testing "Fully populated SearchHit validates"
    (is (m/validate sut/SearchHit
                    {:path "src/a.clj"
                     :score 0.95
                     :snippets [{:start-line 1 :end-line 1 :text "x"}]}))))

(deftest search-hit-rejects-non-double-score-test
  (testing ":score must be a double"
    (is (not (m/validate sut/SearchHit
                         {:path "src/a.clj" :score 1 :snippets []})))))

(deftest search-hit-empty-snippets-valid-test
  (testing "Empty :snippets vector is allowed"
    (is (m/validate sut/SearchHit
                    {:path "src/a.clj" :score 0.0 :snippets []}))))
