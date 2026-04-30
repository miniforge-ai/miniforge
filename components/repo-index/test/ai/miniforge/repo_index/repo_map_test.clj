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

(ns ai.miniforge.repo-index.repo-map-test
  "Tests for repo-map generation. Builds synthetic indexes (no scanner / git
   I/O) so we can isolate the token-budget and grouping logic from any real
   repository state."
  (:require [clojure.test :refer [deftest is testing]]
            [clojure.string :as str]
            [ai.miniforge.repo-index.repo-map :as sut]
            [ai.miniforge.repo-index.factory :as factory]))

;------------------------------------------------------------------------------ Layer 0
;; Test fixtures and factories

(defn- file
  [path lang lines size & {:keys [generated?] :or {generated? false}}]
  (factory/->file-record path "abc1234" size lines lang generated?))

(defn- index-of
  "Build a synthetic index from a vector of file records."
  ([files] (index-of files {}))
  ([files {:keys [tree-sha languages]
           :or   {tree-sha "tree-sha-test"
                  languages {}}}]
   (factory/->repo-index tree-sha "/repo" files languages)))

(defn- big-index
  "Build an index with N files in `dir` such that the budget logic can be
   exercised. Each file has lines=1, size=10 to keep arithmetic simple."
  [dir n]
  (index-of (vec (for [i (range n)]
                   (file (str dir "/f" i ".clj") "clojure" 1 10)))
            {:languages {"clojure" n}}))

;------------------------------------------------------------------------------ Layer 1
;; Empty / trivial inputs

(deftest generate-empty-index-test
  (testing "Empty index produces a slice with zero files shown and not truncated"
    (let [r (sut/generate (index-of []))]
      (is (= 0 (:total-files r)))
      (is (= 0 (:shown-files r)))
      (is (false? (:truncated? r)))
      (is (string? (:text r)))
      (is (pos? (:token-estimate r))) ;; the header still costs tokens
      (is (vector? (:entries r))))))

(deftest generate-default-token-budget-constant-test
  (testing "default-token-budget is the documented 500"
    (is (= 500 sut/default-token-budget))))

;------------------------------------------------------------------------------ Layer 1
;; Single small group fits within budget

(deftest generate-fits-everything-when-budget-is-large-test
  (testing "With a generous budget, all files are shown and not truncated"
    (let [files [(file "src/a.clj" "clojure" 10 100)
                 (file "src/b.clj" "clojure" 20 200)
                 (file "test/c.clj" "clojure" 5 50)]
          r (sut/generate (index-of files {:languages {"clojure" 3}})
                          {:token-budget 5000})]
      (is (= 3 (:total-files r)))
      (is (= 3 (:shown-files r)))
      (is (false? (:truncated? r)))
      (is (= 3 (count (:entries r)))))))

;------------------------------------------------------------------------------ Layer 1
;; Truncation behaviour under a tight budget

(deftest generate-truncates-when-budget-too-small-test
  (testing "With a tight budget, only some files are shown and :truncated? is true"
    (let [r (sut/generate (big-index "src" 50) {:token-budget 80})]
      (is (= 50 (:total-files r)))
      (is (true? (:truncated? r)))
      (is (< (:shown-files r) (:total-files r)))
      ;; The slice's :entries should be exactly :shown-files long.
      (is (= (:shown-files r) (count (:entries r)))))))

(deftest generate-larger-budget-never-reduces-shown-files-test
  (testing "Increasing the budget never reduces the number of shown files
            (monotone, not strictly increasing — when both budgets fit
            everything, both will report the same shown-files count)"
    (let [idx (big-index "src" 100)
          small (sut/generate idx {:token-budget 80})
          large (sut/generate idx {:token-budget 5000})]
      (is (>= (:shown-files large) (:shown-files small))))))

;------------------------------------------------------------------------------ Layer 1
;; Generated-file filtering

(deftest generate-excludes-generated-by-default-test
  (testing "By default, files marked :generated? true are excluded"
    (let [files [(file "src/a.clj" "clojure" 10 100)
                 (file "build/gen.clj" "clojure" 1000 9999 :generated? true)]
          r (sut/generate (index-of files) {:token-budget 5000})]
      (is (= 1 (:total-files r)))
      (is (not (some #(= "build/gen.clj" (:path %)) (:entries r)))))))

(deftest generate-includes-generated-when-opted-in-test
  (testing "Setting :exclude-generated? false includes generated files"
    (let [files [(file "src/a.clj" "clojure" 10 100)
                 (file "build/gen.clj" "clojure" 1 1 :generated? true)]
          r (sut/generate (index-of files) {:token-budget 5000
                                            :exclude-generated? false})]
      (is (= 2 (:total-files r))))))

;------------------------------------------------------------------------------ Layer 1
;; Markdown / grouping shape

(deftest generate-groups-by-top-directory-test
  (testing "The rendered text groups entries under top-directory headers"
    (let [files [(file "src/a.clj" "clojure" 1 10)
                 (file "src/b.clj" "clojure" 1 10)
                 (file "test/c.clj" "clojure" 1 10)
                 (file "README.md" "markdown" 1 10)]
          r (sut/generate (index-of files {:languages {"clojure" 3 "markdown" 1}})
                          {:token-budget 5000})
          text (:text r)]
      (is (str/includes? text "### src/"))
      (is (str/includes? text "### test/"))
      ;; Root-level files group under the synthetic "." directory.
      (is (str/includes? text "### ./")))))

(deftest generate-text-includes-tree-sha-and-counts-test
  (testing "Header includes the tree-sha and the file/line summary"
    (let [files [(file "src/a.clj" "clojure" 5 50)
                 (file "src/b.clj" "clojure" 7 70)]
          r (sut/generate (index-of files {:tree-sha "tree-special-sha"
                                           :languages {"clojure" 2}})
                          {:token-budget 5000})
          text (:text r)]
      (is (str/includes? text "tree-special-sha"))
      ;; "2" file-count and "12" total-lines should both appear in the header.
      (is (str/includes? text "2"))
      (is (str/includes? text "12")))))

(deftest generate-language-summary-includes-each-language-test
  (testing "The header summary lists each language present"
    (let [files [(file "src/a.clj" "clojure" 1 10)
                 (file "src/b.py"  "python"  1 10)
                 (file "src/c.rs"  "rust"    1 10)]
          langs {"clojure" 1 "python" 1 "rust" 1}
          r (sut/generate (index-of files {:languages langs})
                          {:token-budget 5000})
          text (:text r)]
      (doseq [lang (keys langs)]
        (is (str/includes? text lang)
            (str "language " lang " should appear in the header summary"))))))

(deftest generate-entries-sorted-by-path-test
  (testing "Entries are sorted alphabetically by :path within each group"
    (let [files [(file "src/zz.clj" "clojure" 1 10)
                 (file "src/aa.clj" "clojure" 1 10)
                 (file "src/mm.clj" "clojure" 1 10)]
          r (sut/generate (index-of files) {:token-budget 5000})
          paths (map :path (:entries r))]
      (is (= ["src/aa.clj" "src/mm.clj" "src/zz.clj"] paths)))))

;------------------------------------------------------------------------------ Layer 1
;; Token estimate matches structure

(deftest generate-token-estimate-grows-with-shown-files-test
  (testing "More shown files ⇒ larger token-estimate (monotone with capacity)"
    (let [idx (big-index "src" 50)
          small (sut/generate idx {:token-budget 80})
          large (sut/generate idx {:token-budget 5000})]
      (is (<= (:token-estimate small) (:token-estimate large))))))
