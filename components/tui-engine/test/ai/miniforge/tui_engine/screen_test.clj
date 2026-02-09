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

(ns ai.miniforge.tui-engine.screen-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-engine.screen :as screen]))

(deftest mock-screen-lifecycle-test
  (testing "Mock screen starts and stops"
    (let [s (screen/create-mock-screen [80 24])]
      (screen/start-screen! s)
      (is (true? (:started? @(:state s))))
      (screen/stop-screen! s)
      (is (false? (:started? @(:state s)))))))

(deftest mock-screen-size-test
  (testing "Mock screen reports correct size"
    (let [s (screen/create-mock-screen [120 40])]
      (is (= [120 40] (screen/get-size s))))))

(deftest mock-screen-write-read-test
  (testing "Writing to mock screen and reading back"
    (let [s (screen/create-mock-screen [80 24])]
      (screen/put-string! s 0 0 "Hello" :white :black false)
      (is (= "Hello" (subs (screen/mock-read-line s 0 80) 0 5)))))

  (testing "Writing at offset"
    (let [s (screen/create-mock-screen [80 24])]
      (screen/put-string! s 10 5 "Test" :cyan :black true)
      (let [cells (screen/mock-get-cells s)]
        (is (= \T (:char (get cells [10 5]))))
        (is (= \e (:char (get cells [11 5]))))
        (is (= :cyan (:fg (get cells [10 5]))))
        (is (true? (:bold? (get cells [10 5]))))))))

(deftest mock-screen-clear-test
  (testing "Clear removes all cells"
    (let [s (screen/create-mock-screen [80 24])]
      (screen/put-string! s 0 0 "Hello" :white :black false)
      (screen/clear! s)
      (is (empty? (screen/mock-get-cells s))))))

(deftest mock-screen-refresh-test
  (testing "Refresh increments count"
    (let [s (screen/create-mock-screen [80 24])]
      (screen/refresh! s)
      (screen/refresh! s)
      (is (= 2 (:refresh-count @(:state s)))))))

(deftest mock-screen-input-test
  (testing "Poll returns nil when no input queued"
    (let [s (screen/create-mock-screen [80 24])]
      (is (nil? (screen/poll-input s)))))

  (testing "Poll returns queued input in order"
    (let [s (screen/create-mock-screen [80 24])]
      (screen/mock-enqueue-input! s [{:type :character :char \j}
                                     {:type :enter}])
      (is (= {:type :character :char \j} (screen/poll-input s)))
      (is (= {:type :enter} (screen/poll-input s)))
      (is (nil? (screen/poll-input s))))))
