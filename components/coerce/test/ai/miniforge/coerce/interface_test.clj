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

(ns ai.miniforge.coerce.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.coerce.interface :as sut]))

(deftest safe-parse-int-test
  (testing "valid numeric strings parse to ints"
    (is (= 0       (sut/safe-parse-int "0")))
    (is (= 42      (sut/safe-parse-int "42")))
    (is (= -7      (sut/safe-parse-int "-7"))))

  (testing "non-numeric input returns the default"
    (is (nil?  (sut/safe-parse-int "abc")))
    (is (= 99 (sut/safe-parse-int "abc" 99))))

  (testing "nil input returns the default"
    (is (nil?  (sut/safe-parse-int nil)))
    (is (= 0   (sut/safe-parse-int nil 0))))

  (testing "overflow returns the default rather than throwing"
    (is (= -1  (sut/safe-parse-int "99999999999999999999" -1)))))

(deftest safe-parse-long-test
  (testing "valid input"
    (is (= 9999999999 (sut/safe-parse-long "9999999999"))))

  (testing "invalid input returns default"
    (is (nil? (sut/safe-parse-long "x")))
    (is (= 0  (sut/safe-parse-long "x" 0)))))

(deftest safe-parse-double-test
  (testing "valid input"
    (is (= 3.14 (sut/safe-parse-double "3.14"))))

  (testing "invalid input returns default"
    (is (nil?  (sut/safe-parse-double "x")))
    (is (= 0.0 (sut/safe-parse-double "x" 0.0)))))
