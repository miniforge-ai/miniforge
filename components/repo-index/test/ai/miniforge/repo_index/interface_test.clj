(ns ai.miniforge.repo-index.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.repo-index.interface :as repo-index]
            [ai.miniforge.repo-index.scanner :as scanner]
            [ai.miniforge.repo-index.repo-map :as repo-map]
            [ai.miniforge.repo-index.schema :as schema]
            [malli.core :as m]))

(deftest scanner-parse-test
  (testing "detect-language via scanner"
    (let [idx (scanner/scan ".")]
      (is (some? idx) "scan should succeed in a git repo")
      (when idx
        (is (string? (:tree-sha idx)))
        (is (pos? (:file-count idx)))
        (is (pos? (:total-lines idx)))
        (is (map? (:languages idx)))
        (is (vector? (:files idx)))))))

(deftest scanner-schema-test
  (testing "scanned index conforms to RepoIndex schema"
    (let [idx (scanner/scan ".")]
      (when idx
        (is (m/validate schema/RepoIndex idx)
            (str "RepoIndex schema mismatch: "
                 (m/explain schema/RepoIndex idx)))))))

(deftest scanner-file-record-test
  (testing "individual file records conform to FileRecord schema"
    (let [idx (scanner/scan ".")]
      (when idx
        (doseq [f (take 10 (:files idx))]
          (is (m/validate schema/FileRecord f)
              (str "FileRecord schema mismatch for " (:path f) ": "
                   (m/explain schema/FileRecord f))))))))

(deftest repo-map-test
  (testing "repo map generation"
    (let [idx (scanner/scan ".")]
      (when idx
        (let [rmap (repo-map/generate idx)]
          (is (string? (:text rmap)))
          (is (pos? (:token-estimate rmap)))
          (is (pos? (:total-files rmap)))
          (is (pos? (:shown-files rmap)))
          (is (boolean? (:truncated? rmap))))))))

(deftest repo-map-budget-test
  (testing "repo map respects token budget"
    (let [idx (scanner/scan ".")]
      (when idx
        (let [rmap-small (repo-map/generate idx {:token-budget 100})
              rmap-large (repo-map/generate idx {:token-budget 5000})]
          (is (<= (:token-estimate rmap-small) 150)
              "small budget should produce small map (within margin)")
          (is (>= (:shown-files rmap-large) (:shown-files rmap-small))
              "larger budget should show more files"))))))

(deftest build-index-test
  (testing "build-index produces a valid index"
    (let [idx (repo-index/build-index ".")]
      (is (some? idx))
      (when idx
        (is (string? (:tree-sha idx)))
        (is (vector? (:files idx)))))))

(deftest get-file-test
  (testing "get-file retrieves a known file"
    (let [idx (repo-index/build-index ".")]
      (when idx
        (let [result (repo-index/get-file idx "deps.edn")]
          (is (some? result) "deps.edn should exist")
          (when result
            (is (= "deps.edn" (:path result)))
            (is (string? (:content result)))
            (is (pos? (:lines result)))))))))

(deftest get-file-not-in-index-test
  (testing "get-file returns nil for files not in index"
    (let [idx (repo-index/build-index ".")]
      (when idx
        (is (nil? (repo-index/get-file idx "nonexistent-file-xyz.txt")))))))

(deftest files-by-language-test
  (testing "files-by-language filters correctly"
    (let [idx (repo-index/build-index ".")]
      (when idx
        (let [clj-files (repo-index/files-by-language idx "clojure")]
          (is (pos? (count clj-files)) "should find Clojure files")
          (doseq [f clj-files]
            (is (= "clojure" (:language f)))
            (is (not (:generated? f)))))))))
