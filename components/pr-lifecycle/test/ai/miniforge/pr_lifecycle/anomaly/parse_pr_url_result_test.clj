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

(ns ai.miniforge.pr-lifecycle.anomaly.parse-pr-url-result-test
  "Coverage for `responder/parse-pr-url-result` (anomaly-returning) and
   the boundary throw via `responder/respond-to-comments!`.

   Unrecognized URL → `:invalid-input` anomaly. The boundary helper
   escalates via `response/throw-anomaly!` with `:anomalies/incorrect`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.pr-lifecycle.responder :as responder])
  (:import
   (clojure.lang ExceptionInfo)))

;------------------------------------------------------------------------------ Happy path

(deftest parse-pr-url-result-returns-parsed-on-valid-url
  (testing "github URL parses to {:owner :repo :number}"
    (let [result (responder/parse-pr-url-result
                  "https://github.com/miniforge-ai/miniforge/pull/123")]
      (is (not (anomaly/anomaly? result)))
      (is (= "miniforge-ai" (:owner result)))
      (is (= "miniforge" (:repo result)))
      (is (= 123 (:number result))))))

;------------------------------------------------------------------------------ Failure path

(deftest parse-pr-url-result-returns-anomaly-on-non-github-url
  (testing "non-github URL yields :invalid-input anomaly"
    (let [result (responder/parse-pr-url-result "https://gitlab.com/foo/bar/pull/1")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result)))
      (is (= "https://gitlab.com/foo/bar/pull/1" (:url (:anomaly/data result)))))))

(deftest parse-pr-url-result-returns-anomaly-on-malformed-url
  (testing "malformed URL yields :invalid-input anomaly"
    (let [result (responder/parse-pr-url-result "not-a-url")]
      (is (anomaly/anomaly? result))
      (is (= :invalid-input (:anomaly/type result))))))

(deftest parse-pr-url-result-returns-anomaly-on-nil
  (testing "nil URL yields :invalid-input anomaly"
    (is (anomaly/anomaly? (responder/parse-pr-url-result nil)))))

;------------------------------------------------------------------------------ Boundary helper escalates via slingshot

(deftest respond-to-comments-escalates-url-parse-anomaly
  (testing "respond-to-comments! rethrows anomaly as ExceptionInfo"
    (is (thrown-with-msg?
         ExceptionInfo
         #"Could not parse PR number from URL"
         (responder/respond-to-comments! "https://gitlab.com/x/y/pull/1"
                                         "/tmp"
                                         (fn [& _] {})
                                         (fn [] true)
                                         {})))))
