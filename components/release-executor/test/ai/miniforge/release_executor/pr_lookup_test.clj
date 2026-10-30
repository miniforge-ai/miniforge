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
(ns ai.miniforge.release-executor.pr-lookup-test
  (:require
   [ai.miniforge.release-executor.pr-lookup :as pr-lookup]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} parse-lookup-test
  (testing "no PRs"
    (is (nil? (pr-lookup/parse-lookup "")))
    (is (nil? (pr-lookup/parse-lookup nil))))
  (testing "one PR"
    (is (= {:pr-number 7 :pr-url "https://github.com/o/r/pull/7" :pr-state "OPEN"
            :pr-head-sha "abc"}
           (pr-lookup/parse-lookup "7\thttps://github.com/o/r/pull/7\tOPEN\tabc\n"))))
  (testing "open before merged before closed, newest first within a state"
    (is (= 9 (:pr-number (pr-lookup/parse-lookup
                          (str "10\thttps://x/pull/10\tCLOSED\n"
                               "9\thttps://x/pull/9\tMERGED\n"
                               "8\thttps://x/pull/8\tMERGED\n")))))
    (is (= 3 (:pr-number (pr-lookup/parse-lookup
                          (str "5\thttps://x/pull/5\tCLOSED\n"
                               "3\thttps://x/pull/3\tOPEN\n"))))))
  (testing "lines that are not a PR are ignored"
    (is (nil? (pr-lookup/parse-lookup "warning: something\nabc\tdef\tOPEN\n")))))
