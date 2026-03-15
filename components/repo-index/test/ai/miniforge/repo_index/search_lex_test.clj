(ns ai.miniforge.repo-index.search-lex-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.repo-index.interface :as repo-index]
            [ai.miniforge.repo-index.schema :as schema]
            [malli.core :as m]))

(deftest build-search-index-test
  (testing "builds a search index from a repo index"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)]
      (is (some? si))
      (is (pos? (:doc-count si)))
      (is (pos? (:avg-doc-length si)))
      (is (map? (:docs si)))
      (is (map? (:term->doc-ids si)))
      (is (map? (:doc-freq si))))))

(deftest search-basic-test
  (testing "search returns ranked results"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          results (repo-index/search-lex si "scanner language detection")]
      (is (vector? results))
      (is (pos? (count results)))
      (is (every? #(contains? % :path) results))
      (is (every? #(contains? % :score) results))
      (is (every? #(contains? % :snippets) results)))))

(deftest search-schema-test
  (testing "search results conform to SearchHit schema"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          results (repo-index/search-lex si "implement phase")]
      (doseq [hit results]
        (is (m/validate schema/SearchHit hit)
            (str "SearchHit schema mismatch for " (:path hit)))))))

(deftest search-relevance-test
  (testing "search ranks relevant files in top results"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          results (repo-index/search-lex si "repo-index scanner")
          top-paths (set (map :path (take 5 results)))]
      (is (pos? (count results)))
      (is (some #(re-find #"scanner" %) top-paths)
          (str "Expected scanner file in top 5, got: " top-paths)))))

(deftest search-max-results-test
  (testing "search respects :max-results"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          results (repo-index/search-lex si "defn" {:max-results 3})]
      (is (<= (count results) 3)))))

(deftest search-snippets-test
  (testing "snippets contain context around matches"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          results (repo-index/search-lex si "build-search-index")]
      (is (pos? (count results)))
      (let [first-hit (first results)
            snippets (:snippets first-hit)]
        (is (pos? (count snippets)))
        (doseq [s snippets]
          (is (pos? (:start-line s)))
          (is (>= (:end-line s) (:start-line s)))
          (is (string? (:text s))))))))

(deftest search-no-results-test
  (testing "search returns empty vector for nonsense query"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          results (repo-index/search-lex si "xyzzy-nonexistent-gibberish-qwerty")]
      (is (vector? results))
      (is (zero? (count results))))))

(deftest search-scores-descending-test
  (testing "results are sorted by score descending"
    (let [idx (repo-index/build-index ".")
          si (repo-index/build-search-index idx)
          results (repo-index/search-lex si "interface namespace")]
      (when (> (count results) 1)
        (is (apply >= (map :score results)))))))
