;; Copyright 2025 miniforge.ai
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

(ns ai.miniforge.heuristic.core-test
  "Tests for heuristic core functionality."
  (:require
   [clojure.test :refer [deftest is testing use-fixtures]]
   [ai.miniforge.heuristic.core :as heuristic]
   [ai.miniforge.heuristic.store :as store]))

(def test-store (atom nil))

(defn setup-store [f]
  (reset! test-store (store/create-memory-store))
  (f))

(use-fixtures :each setup-store)

(deftest parse-version-test
  (testing "Parse valid version string"
    (is (= {:major 1 :minor 2 :patch 3}
           (heuristic/parse-version "1.2.3")))
    (is (= {:major 0 :minor 1 :patch 0}
           (heuristic/parse-version "0.1.0")))))

(deftest compare-versions-test
  (testing "Compare versions correctly"
    (is (= -1 (heuristic/compare-versions "1.0.0" "1.1.0")))
    (is (= 1 (heuristic/compare-versions "2.0.0" "1.9.0")))
    (is (= 0 (heuristic/compare-versions "1.2.3" "1.2.3")))))

(deftest sort-versions-test
  (testing "Sort versions newest first"
    (is (= ["2.0.0" "1.2.0" "1.1.0" "1.0.0"]
           (heuristic/sort-versions ["1.0.0" "1.2.0" "1.1.0" "2.0.0"])))))

(deftest save-and-get-heuristic-test
  (testing "Save and retrieve heuristic"
    (let [opts {:store @test-store}
          data {:system "You are an implementer"
                :task-template "Implement {{task}}"}]
      (heuristic/save-heuristic :prompt :implementer "1.0.0" data opts)
      (let [retrieved (heuristic/get-heuristic :prompt :implementer "1.0.0" opts)]
        (is (= "You are an implementer" (:system retrieved)))
        (is (= "Implement {{task}}" (:task-template retrieved)))
        (is (= :prompt (:heuristic/type retrieved)))
        (is (= "1.0.0" (:heuristic/version retrieved)))
        (is (:heuristic/saved-at retrieved))))))

(deftest list-versions-test
  (testing "List versions of a heuristic"
    (let [opts {:store @test-store}]
      (heuristic/save-heuristic :prompt :implementer "1.0.0" {:data "v1"} opts)
      (heuristic/save-heuristic :prompt :implementer "1.1.0" {:data "v1.1"} opts)
      (heuristic/save-heuristic :prompt :implementer "2.0.0" {:data "v2"} opts)
      (let [versions (heuristic/list-versions :prompt :implementer opts)]
        (is (= ["2.0.0" "1.1.0" "1.0.0"] versions))))))

(deftest active-version-test
  (testing "Set and get active version"
    (let [opts {:store @test-store}
          data-v1 {:system "Version 1"}
          data-v2 {:system "Version 2"}]
      (heuristic/save-heuristic :prompt :implementer "1.0.0" data-v1 opts)
      (heuristic/save-heuristic :prompt :implementer "2.0.0" data-v2 opts)

      ;; Set active to 1.0.0
      (heuristic/set-active-version :prompt :implementer "1.0.0" opts)
      (let [active (heuristic/get-active-heuristic :prompt :implementer opts)]
        (is (= "Version 1" (:system active))))

      ;; Change active to 2.0.0
      (heuristic/set-active-version :prompt :implementer "2.0.0" opts)
      (let [active (heuristic/get-active-heuristic :prompt :implementer opts)]
        (is (= "Version 2" (:system active)))))))
