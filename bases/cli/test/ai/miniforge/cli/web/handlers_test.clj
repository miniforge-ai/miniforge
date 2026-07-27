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
(ns ai.miniforge.cli.web.handlers-test
  "Regression coverage for the malformed-/api/pr/-path bug found in PR
   review: `parse-pr-path` used to throw NullPointerException /
   NumberFormatException on a short or non-numeric path, crashing
   request handling instead of returning 400. Fixed by having
   parse-pr-path return nil on any parse failure, with every caller
   short-circuiting to a bad-request response."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.cli.web.github :as github]
   [ai.miniforge.cli.web.handlers :as sut]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} parse-pr-path-parses-a-well-formed-path-test
  (testing "repo + numeric PR are extracted and the repo segment is URL-decoded"
    (is (= {:repo "org/repo" :number 42}
           (sut/parse-pr-path "/api/pr/org%2Frepo/42")))))

(deftest ^{:stratum 0} parse-pr-path-returns-nil-on-malformed-paths-test
  (testing "no PR number segment at all"
    (is (nil? (sut/parse-pr-path "/api/pr/"))))
  (testing "repo segment but no PR number segment"
    (is (nil? (sut/parse-pr-path "/api/pr/org%2Frepo"))))
  (testing "non-numeric PR number segment"
    (is (nil? (sut/parse-pr-path "/api/pr/org%2Frepo/not-a-number")))))

(deftest ^{:stratum 0} pr-detail-returns-bad-request-for-a-malformed-path-without-throwing-test
  (testing "malformed path short-circuits to a 400 before touching the github component"
    (with-redefs [github/fetch-prs (fn [& _] (throw (ex-info "should not be called" {})))]
      (is (= {:status 400 :body "Invalid PR path"}
             (sut/pr-detail "/api/pr/org%2Frepo/not-a-number"))))))

(deftest ^{:stratum 0} approve-returns-bad-request-for-a-malformed-path-without-throwing-test
  (testing "malformed path short-circuits to a 400 before touching the github component"
    (with-redefs [github/approve-pr! (fn [& _] (throw (ex-info "should not be called" {})))]
      (is (= {:status 400 :body "Invalid PR path"}
             (sut/approve "/api/pr/"))))))
