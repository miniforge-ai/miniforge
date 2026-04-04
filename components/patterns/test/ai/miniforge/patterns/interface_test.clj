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
