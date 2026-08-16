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
(ns ai.miniforge.buzzword-bingo.board-test
  (:require
   [ai.miniforge.buzzword-bingo.board :as sut]
   [ai.miniforge.buzzword-bingo.interface :as bingo]
   [clojure.string :as str]
   #?(:clj  [clojure.test :refer [deftest is testing]]
      :cljs [cljs.test :refer-macros [deftest is testing]])))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures and helpers
(def ^{:stratum 0} ^:private board-rows 5)

(defn- ^{:stratum 0} session-marking
  "A session whose card squares at `indices` have been marked."
  [indices]
  (let [session (bingo/open-session "board-under-test")
        wanted  (set indices)
        ids     (into [] (comp (filter #(contains? wanted (:square/index %)))
                               (keep :square/id))
                      (:card/squares (:session/card session)))]
    (bingo/track session
                 ;; Weight included because every hit detect/scan emits carries
                 ;; one, and the session sums it.
                 {:scan/hits       (mapv (fn [id] {:hit/id id :hit/weight 3}) ids)
                  :scan/word-count 10})))

(defn- ^{:stratum 0} grid-lines [report]
  (filterv #(str/starts-with? % "  ") (str/split-lines report)))

;; Counted rather than matched on the term itself: a long term is truncated to
;; fit its cell, so the full text is not in the output to look for.
(defn- ^{:stratum 0} bracketed-cells [session]
  (count (re-seq #"\[[^\]]*\]" (sut/board-report session))))

;------------------------------------------------------------------------------ Layer 1

;; The grid
(deftest ^{:stratum 1} test-a-board-is-five-rows-of-five
  (testing "given a session → the card renders as five rows"
    (is (= board-rows (count (grid-lines (sut/board-report (session-marking [])))))))

  (testing "given a session → the free centre square is shown as taken"
    (is (str/includes? (sut/board-report (session-marking [])) "[FREE]"))))

(deftest ^{:stratum 1} test-marks-are-visible-without-colour
  (testing "given nothing marked → only the free square is bracketed"
    (is (= 1 (bracketed-cells (session-marking [])))))

  (testing "given three marked squares → each is bracketed alongside the free one"
    (is (= 4 (bracketed-cells (session-marking [0 1 2]))))))

;; Progress
(deftest ^{:stratum 1} test-progress-counts-squares-on-this-card
  (testing "given marks → the count is of squares on the board, not of every term seen"
    (let [session (session-marking [0 1 2])
          padded  (assoc session :session/marked
                         (into (:session/marked session) [:not-on-this-card :nor-this]))]
      (is (str/includes? (sut/board-report padded) "3 of 24 squares marked")))))

(deftest ^{:stratum 1} test-bingo-is-announced-only-once-a-line-is-complete
  (testing "given no completed line → no announcement"
    (is (not (str/includes? (sut/board-report (session-marking [0 1 2 3])) "BINGO!"))))

  (testing "given a completed row → the announcement and the line count"
    (let [report (sut/board-report (session-marking [0 1 2 3 4]))]
      (is (str/includes? report "BINGO!"))
      (is (str/includes? report "1 line complete")))))
