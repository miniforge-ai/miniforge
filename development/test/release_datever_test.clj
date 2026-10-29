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
(ns release-datever-test
  "`bb release` numbers a day's releases from 1: the first release of a
   day is vYYYY.MM.DD.1, the next one past the highest N already tagged."
  (:require
   [clojure.test :refer [deftest is testing]]
   [release]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} day (java.time.LocalDate/of 2026 10 29))

(defn ^{:stratum 0} today-prefix
  "The DateVer prefix for today, as `release/datever` builds it."
  []
  (let [now (java.time.LocalDate/now)]
    (format "%d.%02d.%02d." (.getYear now) (.getMonthValue now) (.getDayOfMonth now))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} first-release-of-the-day-is-1-test
  (testing "git prints nothing when the day has no tags yet"
    (with-redefs [release/sh (constantly "")]
      (is (= (str (today-prefix) "1") (#'release/datever)))))
  (testing "git failing (sh returns nil) also starts at 1"
    (with-redefs [release/sh (constantly nil)]
      (is (= (str (today-prefix) "1") (#'release/datever))))))

(deftest ^{:stratum 1} next-datever-test
  (testing "no tags for the day"
    (is (= "2026.10.29.1" (release/next-datever day "")))
    (is (= "2026.10.29.1" (release/next-datever day nil))))
  (testing "one past the highest N, in any order, with a trailing newline"
    (is (= "2026.10.29.2" (release/next-datever day "v2026.10.29.1\n")))
    (is (= "2026.10.29.11" (release/next-datever day "v2026.10.29.10\nv2026.10.29.9"))))
  (testing "a gap left by a deleted tag is not reused"
    (is (= "2026.10.29.4" (release/next-datever day "v2026.10.29.1\nv2026.10.29.3"))))
  (testing "tags without a numeric N are ignored"
    (is (= "2026.10.29.2" (release/next-datever day "v2026.10.29.1\nv2026.10.29.rc"))))
  (testing "only a whole vYYYY.MM.DD.N tag counts: a numeric last component after another suffix does not"
    (is (= "2026.10.29.2" (release/next-datever day "v2026.10.29.1\nv2026.10.29.rc.99")))
    (is (= "2026.10.29.2" (release/next-datever day "v2026.10.29.1\nv2026.10.29.1-rc\nv2026.10.29.1.5")))))
