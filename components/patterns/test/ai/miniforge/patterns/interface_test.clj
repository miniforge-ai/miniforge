(ns ai.miniforge.patterns.interface-test
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.patterns.interface :as patterns]))

(deftest md-heading-file-path-test
  (testing "matches file paths in markdown headings"
    (is (= "src/core.clj"
           (second (re-find patterns/md-heading-file-path "### src/core.clj"))))
    (is (= "path/to/file.js"
           (second (re-find patterns/md-heading-file-path "## `path/to/file.js`"))))))

(deftest md-delimited-file-path-test
  (testing "matches file paths in backticks or bold"
    (is (= "src/core.clj"
           (second (re-find patterns/md-delimited-file-path "`src/core.clj`"))))
    (is (= "path/to/file.js"
           (second (re-find patterns/md-delimited-file-path "**path/to/file.js**"))))))

(deftest md-label-file-path-test
  (testing "matches File: or Path: labels"
    (is (= "src/core.clj"
           (second (re-find patterns/md-label-file-path "File: src/core.clj"))))
    (is (= "path/to/file.js"
           (second (re-find patterns/md-label-file-path "Path: `path/to/file.js`"))))))

(deftest rate-limit-test
  (testing "detects rate-limit messages"
    (is (re-find patterns/rate-limit "Error 429: Too many requests"))
    (is (re-find patterns/rate-limit "You've hit your limit"))
    (is (re-find patterns/rate-limit "quota exceeded for this model"))))
