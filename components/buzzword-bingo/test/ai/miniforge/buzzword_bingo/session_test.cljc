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
(ns ai.miniforge.buzzword-bingo.session-test
  (:require
   [ai.miniforge.buzzword-bingo.session :as sut]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(def ^{:stratum 0} ^:private seed "session-under-test")

(defn- ^{:stratum 0} catalog [n]
  (mapv (fn [i]
          {:entry/id       (keyword (str "term-" i))
           :entry/display  (str "term " i)
           :entry/category :probe})
        (range n)))

;; Mirrors the shape detect/scan emits: every hit carries its weight, which is
;; what track sums. Deliberately omits the :score/* keys, so these tests also
;; cover an unscored scan being tracked.
(defn- ^{:stratum 0} scan-of
  [hit-ids & {:keys [words weight] :or {words 20 weight 3}}]
  {:scan/hits       (mapv (fn [id] {:hit/id id :hit/weight weight}) hit-ids)
   :scan/word-count words})

(defn- ^{:stratum 0} ids-at
  "Term ids of the squares of `session` at `indices`."
  [session indices]
  (let [wanted (set indices)]
    (into [] (comp (filter #(contains? wanted (:square/index %)))
                   (keep :square/id))
          (:card/squares (:session/card session)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} fresh-session []
  (sut/open seed (catalog 40)))

;------------------------------------------------------------------------------ Layer 2

;; Playing a turn
(deftest ^{:stratum 2} test-a-turn-accumulates-into-the-session
  (testing "given two turns → marks, counts and totals add up across them"
    (let [session (fresh-session)
          after   (-> session
                      (sut/track (scan-of (ids-at session [0 1]) :words 20 :weight 3))
                      (sut/track (scan-of (ids-at session [2]) :words 30 :weight 3)))]
      (is (= 2 (:session/turns after)))
      (is (= 3 (:session/hit-count after)))
      (is (= 9 (:session/weighted after)))
      (is (= 50 (:session/word-count after)))))

  ;; The weight comes off the hits, so a caller handing track the output of
  ;; detect/scan rather than a scored result still gets a correct total.
  (testing "given hits of differing weight → the session sums what the hits carry"
    (let [session (fresh-session)
          after   (sut/track session (scan-of (ids-at session [0 1]) :weight 5))]
      (is (= 10 (:session/weighted after)))))

  (testing "given a turn with no hits → the session advances but nothing is marked"
    (let [after (sut/track (fresh-session) (scan-of []))]
      (is (= 1 (:session/turns after)))
      (is (empty? (:session/marked after)))
      (is (false? (:session/bingo? after)))))

  (testing "given a session not yet tracked → it already has the shape track returns"
    (let [session (fresh-session)]
      (is (= #{} (:session/new-lines session)))
      (is (false? (:session/bingo? session))))))

;; Completing lines
(deftest ^{:stratum 2} test-a-line-completes-in-any-direction
  (testing "given a full row → that line is complete"
    (let [session (fresh-session)]
      (is (= #{0} (:session/lines (sut/track session (scan-of (ids-at session [0 1 2 3 4]))))))))

  (testing "given a full column → that line is complete"
    (let [session (fresh-session)]
      (is (= #{5} (:session/lines (sut/track session (scan-of (ids-at session [0 5 10 15 20]))))))))

  (testing "given a diagonal → the free centre square supplies the fifth mark"
    (let [session (fresh-session)]
      (is (= #{10} (:session/lines (sut/track session (scan-of (ids-at session [0 6 18 24]))))))))

  (testing "given four of five squares → no line"
    (let [session (fresh-session)]
      (is (empty? (:session/lines (sut/track session (scan-of (ids-at session [0 1 2 3])))))))))

(deftest ^{:stratum 2} test-a-completed-line-is-announced-once
  (testing "given a line completed on an earlier turn → it is not new again"
    (let [session (fresh-session)
          first-turn  (sut/track session (scan-of (ids-at session [0 1 2 3 4])))
          second-turn (sut/track first-turn (scan-of (ids-at session [5 6 7 8 9])))]
      (is (= #{0} (:session/new-lines first-turn)))
      (is (= #{1} (:session/new-lines second-turn)))
      (is (= #{0 1} (:session/lines second-turn)))
      (is (true? (:session/bingo? second-turn)))))

  (testing "given a turn repeating already-marked terms → no line is announced again"
    (let [session (fresh-session)
          marked  (sut/track session (scan-of (ids-at session [0 1 2 3 4])))
          again   (sut/track marked (scan-of (ids-at session [0 1])))]
      (is (empty? (:session/new-lines again)))
      (is (= #{0} (:session/lines again))))))
