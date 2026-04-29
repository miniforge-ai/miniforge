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

(ns ai.miniforge.web-dashboard.views-test
  (:require
   [ai.miniforge.web-dashboard.views :as sut]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

(defn- render-str
  [form]
  (str form))

(deftest title->pane-maps-known-titles
  (testing "known dashboard titles map to stable pane ids"
    (is (= :dashboard (sut/title->pane "Dashboard")))
    (is (= :fleet (sut/title->pane "PR Fleet")))
    (is (= :task-status (sut/title->pane "Task Status")))
    (is (= :evidence (sut/title->pane "Evidence")))
    (is (= :workflows (sut/title->pane "Workflows")))))

(deftest title->pane-defaults-unknown-to-task-status
  (is (= :task-status (sut/title->pane "Unknown"))))

(deftest layout-renders-page-shell
  (let [result (render-str (sut/layout "Dashboard" [:div "Inner body"]))]
    (testing "layout includes title and current pane marker"
      (is (str/includes? result "<title>Miniforge | Dashboard</title>"))
      (is (str/includes? result "data-current-pane=\"dashboard\"")))
    (testing "layout renders the shared shell"
      (is (str/includes? result "Global Filters:"))
      (is (str/includes? result "event-banner"))
      (is (str/includes? result "window.miniforge.filters.shareCurrentView()"))
      (is (str/includes? result "window.miniforge.cycleTheme()")))
    (testing "layout renders supplied body content"
      (is (str/includes? result "Inner body")))))

(deftest layout-marks-active-nav-item
  (let [result (render-str (sut/layout "PR Fleet" [:div "Fleet body"]))]
    (is (str/includes? result "class=\"nav-item active\" href=\"/fleet\""))
    (is (not (str/includes? result "href=\"/\" class=\"active\"")))))

(deftest layout-renders-shared-script-paths
  (let [result (render-str (sut/layout "Dashboard" [:div "Body"]))]
    (is (str/includes? result "/js/app.js"))
    (is (str/includes? result "/js/filters/runtime.js"))
    (is (str/includes? result "/js/filters/init.js"))))
