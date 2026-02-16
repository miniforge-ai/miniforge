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
  (testing "Known theme keys resolve to non-default values"
    (is (not= :default (style/resolve-color style/default-theme :status/running)))
    (is (= :green (style/resolve-color style/default-theme :status/success)))
    (is (not= :default (style/resolve-color style/default-theme :status/failed))))

  (testing "Unknown keys fall back to :default"
    (is (= :default (style/resolve-color style/default-theme :nonexistent)))))

(deftest resolve-style-test
  (testing "Resolves fg, bg, bold from theme"
    (let [result (style/resolve-style style/high-contrast-theme
                                      {:fg :status/running :bg :bg :bold? true})]
      (is (= :cyan (:fg result)))
      (is (= :black (:bg result)))
      (is (true? (:bold? result)))))

  (testing "Defaults when keys omitted"
    (let [result (style/resolve-style style/default-theme {})]
      (is (some? (:fg result)))
      (is (some? (:bg result)))
      (is (false? (:bold? result))))))

(deftest get-theme-test
  (testing "Known theme keys resolve to theme maps"
    (is (= style/dark-theme (style/get-theme :dark)))
    (is (= style/light-theme (style/get-theme :light)))
    (is (= style/high-contrast-theme (style/get-theme :high-contrast)))
    (is (= style/high-contrast-light-theme (style/get-theme :high-contrast-light)))
    (is (= style/dark-theme (style/get-theme :default))))

  (testing "Unknown theme key falls back to dark-theme"
    (is (= style/dark-theme (style/get-theme :nonexistent)))))

(deftest themes-registry-test
  (testing "Themes registry contains all expected keys"
    (is (contains? style/themes :dark))
    (is (contains? style/themes :light))
    (is (contains? style/themes :high-contrast))
    (is (contains? style/themes :high-contrast-light))
    (is (contains? style/themes :default)))

  (testing "High-contrast dark has keyword colors (ANSI only)"
    (is (= :black (:bg style/high-contrast-theme)))
    (is (= :white (:fg style/high-contrast-theme))))

  (testing "High-contrast light has keyword colors (ANSI only)"
    (is (= :white (:bg style/high-contrast-light-theme)))
    (is (= :black (:fg style/high-contrast-light-theme))))

  (testing "Brand dark theme uses RGB vectors"
    (is (vector? (:bg style/dark-theme)))
    (is (vector? (:fg style/dark-theme))))

  (testing "Brand light theme uses RGB vectors"
    (is (vector? (:bg style/light-theme)))
    (is (vector? (:fg style/light-theme)))))

(def ^:private required-theme-keys
  #{:bg :fg :border :title :header :selected-bg :selected-fg
    :status/running :status/success :status/failed
    :status/blocked :status/pending
    :progress-fill :progress-empty
    :kanban/blocked :kanban/pending :kanban/running :kanban/done
    :sparkline :command-bg :command-fg :search-match})

(deftest all-themes-completeness-test
  (testing "Every theme has all required keys"
    (doseq [[theme-name theme-map] style/themes
            :when (not= theme-name :default)]
      (doseq [k required-theme-keys]
        (is (contains? theme-map k)
            (str "Theme " theme-name " missing key: " k))))))

(deftest degrade-color-test
  (testing "Keywords pass through at all depths"
    (is (= :cyan (style/degrade-color :cyan :true-color)))
    (is (= :cyan (style/degrade-color :cyan :256)))
    (is (= :cyan (style/degrade-color :cyan :16))))

  (testing "RGB passes through at true-color"
    (is (= [202 105 30] (style/degrade-color [202 105 30] :true-color))))

  (testing "RGB degrades to integer at 256-color"
    (let [result (style/degrade-color [202 105 30] :256)]
      (is (integer? result))
      (is (<= 0 result 255))))

  (testing "RGB degrades to keyword at 16-color"
    (let [result (style/degrade-color [202 105 30] :16)]
      (is (keyword? result))))

  (testing "Integer passes through at 256-color"
    (is (= 166 (style/degrade-color 166 :256))))

  (testing "Integer degrades to keyword at 16-color"
    (let [result (style/degrade-color 166 :16)]
      (is (keyword? result)))))

(deftest resolve-theme-colors-test
  (testing "True-color passes through unchanged"
    (is (= style/dark-theme
           (style/resolve-theme-colors style/dark-theme :true-color))))

  (testing "16-color degrades all RGB to keywords"
    (let [degraded (style/resolve-theme-colors style/dark-theme :16)]
      (doseq [[k v] degraded]
        (is (keyword? v)
            (str "Key " k " should be keyword at 16-color, got: " (type v)))))))

(deftest miniforge-palette-test
  (testing "Palette contains brand swatches"
    (is (= [89 89 89]     (:steel style/miniforge-palette)))
    (is (= [242 185 12]   (:gold style/miniforge-palette)))
    (is (= [242 84 27]    (:flame style/miniforge-palette)))
    (is (= [242 34 15]    (:hot style/miniforge-palette)))
    (is (= [217 217 217]  (:ash style/miniforge-palette)))))
