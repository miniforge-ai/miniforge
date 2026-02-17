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

(ns ai.miniforge.tui-engine.input-test
  (:require
   [clojure.edn :as edn]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-engine.input :as input]))

(deftest normalize-key-test
  (testing "Character keys return {key char} maps with semantic keyword"
    (is (= {:key :key/j :char \j} (input/normalize-key {:type :character :char \j})))
    (is (= {:key :key/k :char \k} (input/normalize-key {:type :character :char \k})))
    (is (= {:key :key/q :char \q} (input/normalize-key {:type :character :char \q})))
    (is (= {:key :key/slash :char \/} (input/normalize-key {:type :character :char \/})))
    (is (= {:key :key/colon :char \:} (input/normalize-key {:type :character :char \:})))
    (is (= {:key :key/space :char \space} (input/normalize-key {:type :character :char \space}))))

  (testing "Number keys return {key char} maps"
    (is (= {:key :key/d1 :char \1} (input/normalize-key {:type :character :char \1})))
    (is (= {:key :key/d5 :char \5} (input/normalize-key {:type :character :char \5})))
    (is (= {:key :key/d9 :char \9} (input/normalize-key {:type :character :char \9})))
    (is (= {:key :key/d0 :char \0} (input/normalize-key {:type :character :char \0}))))

  (testing "Mapped letter keys include both semantic and raw char"
    (is (= {:key :key/r :char \r} (input/normalize-key {:type :character :char \r})))
    (is (= {:key :key/s :char \s} (input/normalize-key {:type :character :char \s})))
    (is (= {:key :key/b :char \b} (input/normalize-key {:type :character :char \b})))
    (is (= {:key :key/e :char \e} (input/normalize-key {:type :character :char \e})))
    (is (= {:key :key/f :char \f} (input/normalize-key {:type :character :char \f})))
    (is (= {:key :key/o :char \o} (input/normalize-key {:type :character :char \o})))
    (is (= {:key :key/x :char \x} (input/normalize-key {:type :character :char \x})))
    (is (= {:key :key/a :char \a} (input/normalize-key {:type :character :char \a})))
    (is (= {:key :key/y :char \y} (input/normalize-key {:type :character :char \y})))
    (is (= {:key :key/n :char \n} (input/normalize-key {:type :character :char \n}))))

  (testing "Special keys return bare keywords"
    (is (= :key/enter (input/normalize-key {:type :enter})))
    (is (= :key/escape (input/normalize-key {:type :escape})))
    (is (= :key/backspace (input/normalize-key {:type :backspace})))
    (is (= :key/up (input/normalize-key {:type :arrow-up})))
    (is (= :key/down (input/normalize-key {:type :arrow-down})))
    (is (= :key/left (input/normalize-key {:type :arrow-left})))
    (is (= :key/right (input/normalize-key {:type :arrow-right})))
    (is (= :key/tab (input/normalize-key {:type :tab})))
    (is (= :key/shift-tab (input/normalize-key {:type :reverse-tab})))
    (is (= :key/eof (input/normalize-key {:type :eof}))))

  (testing "Unmapped characters return {key nil char ch}"
    (let [result (input/normalize-key {:type :character :char \z})]
      (is (= {:key nil :char \z} result)))
    (let [result (input/normalize-key {:type :character :char \m})]
      (is (= {:key nil :char \m} result))))

  (testing "Unknown event types return nil"
    (is (nil? (input/normalize-key {:type :unknown-key-type})))
    (is (nil? (input/normalize-key {:type :f1}))))

  (testing "nil input returns nil"
    (is (nil? (input/normalize-key nil)))))

(deftest key-tokens-edn-test
  (testing "key-tokens.edn is loadable and well-formed"
    (let [tokens (-> (io/resource "config/tui/key-tokens.edn")
                     slurp
                     edn/read-string)]
      (is (map? (:char-keys tokens))
          "char-keys section exists and is a map")
      (is (map? (:special-keys tokens))
          "special-keys section exists and is a map")

      (testing "all char-keys are single-char strings -> :key/* keywords"
        (doseq [[s token] (:char-keys tokens)]
          (is (string? s) (str "char-key key must be a string: " s))
          (is (= 1 (count s)) (str "char-key must be single char: " s))
          (is (= "key" (namespace token))
              (str "char-key value must be :key/* keyword: " token))))

      (testing "all special-keys are keyword -> :key/* keyword"
        (doseq [[event-type token] (:special-keys tokens)]
          (is (keyword? event-type)
              (str "special-key key must be a keyword: " event-type))
          (is (= "key" (namespace token))
              (str "special-key value must be :key/* keyword: " token))))

      (testing "char-keys and special-keys cover expected tokens"
        (let [all-tokens (set (concat (vals (:char-keys tokens))
                                      (vals (:special-keys tokens))))]
          (is (contains? all-tokens :key/j))
          (is (contains? all-tokens :key/enter))
          (is (contains? all-tokens :key/escape))
          (is (contains? all-tokens :key/tab)))))))
