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
    (is (not= :default (style/resolve-color style/default-theme :status/success)))
    (is (not= :default (style/resolve-color style/default-theme :status/failed))))

  (testing "Unknown keys fall back to :default"
    (is (= :default (style/resolve-color style/default-theme :nonexistent)))))

(deftest resolve-style-test
  (testing "Resolves fg, bg, bold from theme"
    (let [hc (style/get-theme :high-contrast)
          result (style/resolve-style hc {:fg :status/running :bg :bg :bold? true})]
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
    (is (map? (style/get-theme :dark)))
    (is (map? (style/get-theme :light)))
    (is (map? (style/get-theme :high-contrast)))
    (is (map? (style/get-theme :high-contrast-light)))
    (is (map? (style/get-theme :default))))

  (testing "Default resolves to dark theme"
    (is (= (style/get-theme :dark) (style/get-theme :default))))

  (testing "Unknown theme key falls back to dark theme"
    (is (= (style/get-theme :dark) (style/get-theme :nonexistent)))))

(deftest themes-registry-test
  (testing "Themes registry contains all expected keys"
    (is (contains? style/themes :dark))
    (is (contains? style/themes :light))
    (is (contains? style/themes :high-contrast))
    (is (contains? style/themes :high-contrast-light))
    (is (contains? style/themes :default)))

  (testing "High-contrast dark has keyword colors (ANSI only)"
    (let [hc (style/get-theme :high-contrast)]
      (is (= :black (:bg hc)))
      (is (= :white (:fg hc)))))

  (testing "High-contrast light has keyword colors (ANSI only)"
    (let [hcl (style/get-theme :high-contrast-light)]
      (is (= :white (:bg hcl)))
      (is (= :black (:fg hcl)))))

  (testing "Brand dark theme uses RGB vectors"
    (let [dark (style/get-theme :dark)]
      (is (vector? (:bg dark)))
      (is (vector? (:fg dark)))))

  (testing "Brand light theme uses RGB vectors"
    (let [light (style/get-theme :light)]
      (is (vector? (:bg light)))
      (is (vector? (:fg light))))))

(def required-theme-keys
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

(deftest themes-loaded-from-edn-test
  (testing "Themes are loaded from config/tui/themes.edn"
    (is (= 5 (count style/themes))
        "Expected 5 theme entries: :dark :light :high-contrast :high-contrast-light :default")
    (is (map? (:dark style/themes)))
    (is (map? (:light style/themes)))))

(deftest brand-palette-values-test
  (testing "Dark theme contains miniforge brand colors"
    (let [dark (style/get-theme :dark)]
      ;; Charcoal background
      (is (= [35 30 28] (:bg dark)))
      ;; Ash foreground
      (is (= [217 217 217] (:fg dark)))
      ;; Gold accent
      (is (= [242 185 12] (:status/running dark)))
      ;; Flame accent
      (is (= [242 84 27] (:header dark)))))

  (testing "Light theme contains miniforge brand colors"
    (let [light (style/get-theme :light)]
      ;; Parchment background
      (is (= [240 235 225] (:bg light)))
      ;; Charcoal foreground
      (is (= [35 30 28] (:fg light)))
      ;; Ember accent
      (is (= [180 60 20] (:header light))))))

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
    (let [dark (style/get-theme :dark)]
      (is (= dark (style/resolve-theme-colors dark :true-color)))))

  (testing "16-color degrades all RGB to keywords"
    (let [dark (style/get-theme :dark)
          degraded (style/resolve-theme-colors dark :16)]
      (doseq [[k v] degraded]
        (is (keyword? v)
            (str "Key " k " should be keyword at 16-color, got: " (type v)))))))
