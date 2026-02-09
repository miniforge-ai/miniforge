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

(ns ai.miniforge.tui-engine.style-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-engine.style :as style]))

(deftest resolve-color-test
  (testing "Known theme keys resolve"
    (is (= :cyan (style/resolve-color style/default-theme :status/running)))
    (is (= :green (style/resolve-color style/default-theme :status/success)))
    (is (= :red (style/resolve-color style/default-theme :status/failed))))

  (testing "Unknown keys fall back to :default"
    (is (= :default (style/resolve-color style/default-theme :nonexistent)))))

(deftest resolve-style-test
  (testing "Resolves fg, bg, bold from theme"
    (let [result (style/resolve-style style/default-theme
                                      {:fg :status/running :bg :bg :bold? true})]
      (is (= :cyan (:fg result)))
      (is (= :black (:bg result)))
      (is (true? (:bold? result)))))

  (testing "Defaults when keys omitted"
    (let [result (style/resolve-style style/default-theme {})]
      (is (= :white (:fg result)))
      (is (= :black (:bg result)))
      (is (false? (:bold? result))))))

(deftest default-theme-completeness-test
  (testing "Theme has all required keys"
    (let [required #{:bg :fg :border :title :header :selected-bg :selected-fg
                     :status/running :status/success :status/failed
                     :status/blocked :status/pending
                     :progress-fill :progress-empty
                     :kanban/blocked :kanban/pending :kanban/running :kanban/done
                     :sparkline :command-bg :command-fg :search-match}]
      (doseq [k required]
        (is (contains? style/default-theme k)
            (str "Missing theme key: " k))))))
