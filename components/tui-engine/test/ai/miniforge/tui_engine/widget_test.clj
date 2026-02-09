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

(ns ai.miniforge.tui-engine.widget-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-engine.widget :as widget]
   [ai.miniforge.tui-engine.layout :as layout]))

(deftest status-indicator-test
  (testing "Status chars render correctly"
    (let [buf (widget/status-indicator :running)]
      (is (= \● (:char (get-in buf [0 0])))))
    (let [buf (widget/status-indicator :success)]
      (is (= \✓ (:char (get-in buf [0 0])))))
    (let [buf (widget/status-indicator :failed)]
      (is (= \✗ (:char (get-in buf [0 0]))))))

  (testing "Status colors are correct"
    (is (= :cyan (:fg (get-in (widget/status-indicator :running) [0 0]))))
    (is (= :green (:fg (get-in (widget/status-indicator :success) [0 0]))))
    (is (= :red (:fg (get-in (widget/status-indicator :failed) [0 0]))))))

(deftest status-text-test
  (testing "Status text shows indicator and label"
    (let [strings (layout/buf->strings (widget/status-text [20 1] :running "Active"))]
      (is (= \● (first (first strings))))
      (is (str/includes? (first strings) "Active")))))

(deftest progress-bar-test
  (testing "0% progress"
    (let [buf (widget/progress-bar [20 1] {:percent 0})
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "0%"))))

  (testing "100% progress"
    (let [buf (widget/progress-bar [20 1] {:percent 100})
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "100%"))
      ;; All bar chars should be filled
      (is (every? #(= \█ %)
                   (take 15 (map :char (first buf)))))))

  (testing "50% progress has mix of filled and empty"
    (let [buf (widget/progress-bar [20 1] {:percent 50})
          chars (map :char (first buf))
          filled (count (filter #(= \█ %) chars))
          empty (count (filter #(= \░ %) chars))]
      (is (pos? filled))
      (is (pos? empty))))

  (testing "Clamping beyond 100"
    (let [strings (layout/buf->strings (widget/progress-bar [20 1] {:percent 150}))]
      (is (str/includes? (first strings) "100%"))))

  (testing "Clamping below 0"
    (let [strings (layout/buf->strings (widget/progress-bar [20 1] {:percent -10}))]
      (is (str/includes? (first strings) "0%")))))

(deftest tree-test
  (testing "Tree renders nodes with indentation"
    (let [nodes [{:label "Root" :depth 0 :expandable? true}
                 {:label "Child 1" :depth 1 :expandable? false}
                 {:label "Child 2" :depth 1 :expandable? true}]
          buf (widget/tree [30 5] {:nodes nodes :expanded #{0} :selected 0})
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "▼"))
      (is (str/includes? (first strings) "Root"))
      (is (str/includes? (second strings) "Child 1"))))

  (testing "Collapsed node shows right arrow"
    (let [nodes [{:label "Folder" :depth 0 :expandable? true}]
          buf (widget/tree [30 3] {:nodes nodes :expanded #{} :selected 0})
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "▸")))))

(deftest kanban-test
  (testing "Kanban renders columns with titles"
    (let [columns [{:title "BLOCKED" :color :red :cards [{:label "task-1" :status :blocked}]}
                   {:title "RUNNING" :color :cyan :cards [{:label "task-2" :status :running}]}
                   {:title "DONE" :color :green :cards [{:label "task-3" :status :success}]}]
          buf (widget/kanban [60 10] {:columns columns})
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "BLOCKED"))
      (is (str/includes? (first strings) "RUNNING"))
      (is (str/includes? (first strings) "DONE")))))

(deftest scrollable-test
  (testing "Scrollable shows correct viewport"
    (let [lines (mapv #(str "Line " %) (range 100))
          buf (widget/scrollable [30 5] {:lines lines :offset 10})
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "Line 10"))
      (is (str/includes? (last strings) "Line 14"))))

  (testing "Scrollbar present on right edge"
    (let [lines (mapv #(str "Line " %) (range 100))
          buf (widget/scrollable [30 5] {:lines lines :offset 0})
          ;; Last column should have scrollbar characters
          last-col-chars (mapv #(:char (get-in buf [% 29])) (range 5))]
      (is (some #(or (= % \▮) (= % \│)) last-col-chars)))))

(deftest sparkline-test
  (testing "Sparkline renders braille characters"
    (let [buf (widget/sparkline [10 1] {:values [1 3 5 2 8 4 6 9 3 7]})
          chars (mapv #(:char %) (first buf))]
      ;; All should be braille characters
      (is (every? #(>= (int %) 0x2800) chars))))

  (testing "Empty values produce empty sparkline"
    (let [buf (widget/sparkline [5 1] {:values []})
          chars (mapv #(:char %) (first buf))]
      (is (= 5 (count chars)))))

  (testing "Single value fills to max"
    (let [buf (widget/sparkline [3 1] {:values [5 5 5]})
          chars (mapv #(:char %) (first buf))]
      (is (every? #(= \⣿ %) chars)))))
