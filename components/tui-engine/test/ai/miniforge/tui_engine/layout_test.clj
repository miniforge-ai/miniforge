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

(ns ai.miniforge.tui-engine.layout-test
  (:require
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-engine.layout :as layout]))

(deftest text-test
  (testing "Simple text rendering"
    (let [buf (layout/text [20 1] "Hello")]
      (is (= ["Hello               "] (layout/buf->strings buf)))))

  (testing "Truncation at width boundary"
    (let [buf (layout/text [5 1] "Hello, world!")]
      (is (= ["Hell…"] (layout/buf->strings buf)))))

  (testing "Multi-line text"
    (let [buf (layout/text [10 3] "Line 1\nLine 2\nLine 3")]
      (is (= ["Line 1    " "Line 2    " "Line 3    "]
             (layout/buf->strings buf)))))

  (testing "Text truncated to available rows"
    (let [buf (layout/text [10 2] "Line 1\nLine 2\nLine 3")]
      (is (= 2 (count (layout/buf->strings buf))))))

  (testing "Right-aligned text"
    (let [buf (layout/text [10 1] "Hi" {:align :right})]
      (is (= ["        Hi"] (layout/buf->strings buf)))))

  (testing "Center-aligned text"
    (let [buf (layout/text [10 1] "Hi" {:align :center})]
      (is (= ["    Hi    "] (layout/buf->strings buf))))))

(deftest box-test
  (testing "Simple box renders corners and borders"
    (let [strings (layout/buf->strings (layout/box [10 4]))]
      (is (= \┌ (first (first strings))))
      (is (= \┐ (last (first strings))))
      (is (= \└ (first (last strings))))
      (is (= \┘ (last (last strings))))))

  (testing "Box with title"
    (let [strings (layout/buf->strings (layout/box [20 4] {:title "Test"}))]
      (is (str/includes? (first strings) "Test"))))

  (testing "Double border box"
    (let [strings (layout/buf->strings (layout/box [10 3] {:border :double}))]
      (is (= \╔ (first (first strings))))
      (is (= \╗ (last (first strings))))))

  (testing "Box with content"
    (let [strings (layout/buf->strings
                   (layout/box [12 4]
                               {:content-fn
                                (fn [size] (layout/text size "inner"))}))]
      (is (str/includes? (second strings) "inner"))))

  (testing "Too-small box returns nil"
    (is (nil? (layout/box [1 1])))))

(deftest split-h-test
  (testing "Horizontal split divides columns"
    (let [buf (layout/split-h [20 3] 0.5
                              (fn [size] (layout/text size "LEFT"))
                              (fn [size] (layout/text size "RIGHT")))
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "LEFT"))
      (is (str/includes? (first strings) "RIGHT")))))

(deftest split-v-test
  (testing "Vertical split divides rows"
    (let [buf (layout/split-v [20 4] 0.5
                              (fn [size] (layout/text size "TOP"))
                              (fn [size] (layout/text size "BOTTOM")))
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "TOP"))
      (is (str/includes? (nth strings 2) "BOTTOM")))))

(deftest table-test
  (testing "Table renders headers and data"
    (let [columns [{:key :name :header "Name" :width 10}
                   {:key :status :header "Status" :width 8}]
          data [{:name "workflow-1" :status "running"}
                {:name "workflow-2" :status "done"}]
          buf (layout/table [18 6] {:columns columns :data data})
          strings (layout/buf->strings buf)]
      (is (str/includes? (first strings) "Name"))
      (is (str/includes? (first strings) "Status"))
      (is (str/includes? (nth strings 2) "workflow-1"))))

  (testing "Table with selected row"
    (let [columns [{:key :name :header "Name" :width 10}]
          data [{:name "first"} {:name "second"}]
          buf (layout/table [10 5] {:columns columns :data data :selected-row 1})
          ;; Selected row (index 1) is at render row 3
          cell (get-in buf [3 0])]
      (is (= :blue (:bg cell))))))

(deftest pad-test
  (testing "Padding adds space around content"
    (let [buf (layout/pad [10 5] [1 1 1 1]
                          (fn [size] (layout/text size "X")))
          strings (layout/buf->strings buf)]
      ;; First row should be all spaces (top padding)
      (is (= "          " (first strings)))
      ;; Content should be offset by 1 col
      (is (= \X (nth (second strings) 1))))))

(deftest make-buffer-test
  (testing "Buffer has correct dimensions"
    (let [buf (layout/make-buffer [5 3])]
      (is (= 3 (count buf)))
      (is (= 5 (count (first buf)))))))

(deftest minimum-size-test
  (testing "80x24 minimum renders without error"
    (let [buf (layout/text [80 24] "Minimum terminal size test")]
      (is (= 24 (count buf)))
      (is (= 80 (count (first buf)))))))
