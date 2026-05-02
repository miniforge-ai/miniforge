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

(ns ai.miniforge.clock.interface-test
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.clock.interface :as sut]))

(deftest now-ms-test
  (testing "returns a positive long that increases monotonically across two calls"
    (let [a (sut/now-ms)
          b (sut/now-ms)]
      (is (pos-int? a))
      (is (>= b a)))))

(deftest elapsed-since-test
  (testing "elapsed is computed against current now-ms"
    (with-redefs [sut/now-ms (constantly 1000)]
      (is (= 250 (sut/elapsed-since 750)))))

  (testing "negative skew clamps to negative (caller is responsible for forward times)"
    (with-redefs [sut/now-ms (constantly 1000)]
      (is (= -50 (sut/elapsed-since 1050))))))

(deftest with-duration-test
  (testing "returns the body result + elapsed millis"
    (with-redefs [sut/now-ms (let [calls (atom 0)]
                               (fn []
                                 (swap! calls inc)
                                 (case @calls
                                   1 100
                                   2 175
                                   nil)))]
      (let [out (sut/with-duration
                  (+ 1 2 3))]
        (is (= 6   (:result out)))
        (is (= 75  (:duration-ms out))))))

  (testing "body executes once, even with multiple forms"
    (let [counter (atom 0)
          out     (sut/with-duration
                    (swap! counter inc)
                    (swap! counter inc))]
      (is (= 2 (:result out)))
      (is (= 2 @counter)))))
