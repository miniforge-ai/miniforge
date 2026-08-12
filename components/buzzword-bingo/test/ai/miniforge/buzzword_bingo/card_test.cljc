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
(ns ai.miniforge.buzzword-bingo.card-test
  (:require
   [ai.miniforge.buzzword-bingo.card :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
;; Mirrors the shape entry/compile-all emits, minus the compiled pattern the
;; card never looks at.
(defn- ^{:stratum 0} catalog
  "A catalog of `n` distinct entries."
  [n]
  (mapv (fn [i]
          {:entry/id       (keyword (str "term-" i))
           :entry/display  (str "term " i)
           :entry/category :probe})
        (range n)))

(defn- ^{:stratum 0} terms-on [card]
  (mapv :square/term (:card/squares card)))

(deftest ^{:stratum 0} test-ids-differing-only-by-namespace-stay-distinct
  (testing "given ids sharing a name across namespaces → every one is dealt, none merged"
    (let [namespaced (mapv (fn [i]
                             {:entry/id       (keyword (str "ns-" i) "same-name")
                              :entry/display  (str "term " i)
                              :entry/category :probe})
                           (range 30))]
      (is (= (dec sut/square-count)
             (count (sut/ids-on (sut/card-for "seed" namespaced))))))))

;------------------------------------------------------------------------------ Layer 1

;; Dealing
(deftest ^{:stratum 1} test-a-card-is-a-full-board
  (testing "given a catalog → the card has every square, in index order"
    (let [card (sut/card-for "seed" (catalog 40))]
      (is (= sut/square-count (count (:card/squares card))))
      (is (= (range sut/square-count) (map :square/index (:card/squares card))))))

  (testing "given a card → the centre square is free and carries no term"
    (let [centre (nth (:card/squares (sut/card-for "seed" (catalog 40)))
                      sut/free-square-index)]
      (is (:square/free? centre))
      (is (nil? (:square/id centre)))))

  (testing "given a card → no term is dealt twice"
    (let [ids (sut/ids-on (sut/card-for "seed" (catalog 40)))]
      (is (= (dec sut/square-count) (count ids))))))

(deftest ^{:stratum 1} test-dealing-is-determined-by-the-seed
  (testing "given the same seed and catalog → the same card, so it need not be stored"
    (is (= (sut/card-for "seed" (catalog 40))
           (sut/card-for "seed" (catalog 40)))))

  (testing "given a different seed → a different arrangement"
    (is (not= (terms-on (sut/card-for "one" (catalog 40)))
              (terms-on (sut/card-for "two" (catalog 40))))))

  (testing "given a catalog in a different order → the same card, since order is derived"
    (is (= (sut/card-for "seed" (catalog 40))
           (sut/card-for "seed" (reverse (catalog 40)))))))

(deftest ^{:stratum 1} test-a-catalog-too-small-to-fill-a-card-is-refused
  (testing "given fewer entries than squares → the caller is told rather than dealt a gap"
    (is (thrown? #?(:clj Exception :cljs js/Error)
                 (sut/card-for "seed" (catalog 10))))))
