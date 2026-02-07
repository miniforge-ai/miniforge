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

(ns ai.miniforge.tui-engine.input-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-engine.input :as input]))

(deftest normalize-key-test
  (testing "Character keys map to semantic keywords"
    (is (= :key/j (input/normalize-key {:type :character :char \j})))
    (is (= :key/k (input/normalize-key {:type :character :char \k})))
    (is (= :key/q (input/normalize-key {:type :character :char \q})))
    (is (= :key/slash (input/normalize-key {:type :character :char \/})))
    (is (= :key/colon (input/normalize-key {:type :character :char \:})))
    (is (= :key/space (input/normalize-key {:type :character :char \space}))))

  (testing "Number keys map correctly"
    (is (= :key/d1 (input/normalize-key {:type :character :char \1})))
    (is (= :key/d5 (input/normalize-key {:type :character :char \5}))))

  (testing "Special keys map correctly"
    (is (= :key/enter (input/normalize-key {:type :enter})))
    (is (= :key/escape (input/normalize-key {:type :escape})))
    (is (= :key/backspace (input/normalize-key {:type :backspace})))
    (is (= :key/up (input/normalize-key {:type :arrow-up})))
    (is (= :key/down (input/normalize-key {:type :arrow-down})))
    (is (= :key/left (input/normalize-key {:type :arrow-left})))
    (is (= :key/right (input/normalize-key {:type :arrow-right})))
    (is (= :key/tab (input/normalize-key {:type :tab})))
    (is (= :key/eof (input/normalize-key {:type :eof}))))

  (testing "Unmapped characters return char map"
    (let [result (input/normalize-key {:type :character :char \x})]
      (is (= {:type :char :char \x} result))))

  (testing "nil input returns nil"
    (is (nil? (input/normalize-key nil)))))
