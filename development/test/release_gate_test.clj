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
(ns release-gate-test
  "`bb release` tags a commit \"Stable\" without running tests, so it
   must refuse unless that commit's ci.yml push run on main passed."
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [release-gate :as sut]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} sha "e666d68d7ae93f40340607a4658a860c15089519")

(deftest ^{:stratum 0} parse-run-lines-test
  (is (= [{:id 39567822402 :status "completed" :conclusion "failure"}
          {:id 39567797658 :status "in_progress" :conclusion nil}]
         (sut/parse-run-lines "39567822402 completed failure\n39567797658 in_progress null\n")))
  (is (= [] (sut/parse-run-lines ""))))

(deftest ^{:stratum 0} ci-verdict-test
  (testing "the newest run decides: a pass is green"
    (is (= :green (sut/ci-verdict [{:id 2 :status "completed" :conclusion "success"}
                                   {:id 1 :status "completed" :conclusion "failure"}]))))
  (testing "a newer failure outweighs an older pass"
    (is (= :red (sut/ci-verdict [{:id 1 :status "completed" :conclusion "success"}
                                 {:id 2 :status "completed" :conclusion "failure"}]))))
  (testing "a newest run still going is pending"
    (is (= :pending (sut/ci-verdict [{:id 1 :status "completed" :conclusion "success"}
                                     {:id 2 :status "in_progress" :conclusion nil}]))))
  (testing "cancelled is not a pass"
    (is (= :red (sut/ci-verdict [{:id 1 :status "completed" :conclusion "cancelled"}]))))
  (testing "no run at all is missing"
    (is (= :missing (sut/ci-verdict [])))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} ci-blocker-test
  (testing "green CI lets the release through"
    (with-redefs [sut/ci-runs (fn [_] {:runs [{:id 1 :status "completed" :conclusion "success"}]})]
      (is (nil? (sut/ci-blocker sha)))))
  (testing "red, pending, and missing CI each block, naming the commit"
    (doseq [runs [[{:id 1 :status "completed" :conclusion "failure"}]
                  [{:id 1 :status "queued" :conclusion nil}]
                  []]]
      (with-redefs [sut/ci-runs (fn [_] {:runs runs})]
        (let [blocker (sut/ci-blocker sha)]
          (is (string? blocker))
          (is (str/includes? blocker sha))))))
  (testing "a failed lookup blocks rather than passing"
    (with-redefs [sut/ci-runs (fn [_] {:error "HTTP 401"})]
      (is (str/includes? (sut/ci-blocker sha) "HTTP 401")))))
