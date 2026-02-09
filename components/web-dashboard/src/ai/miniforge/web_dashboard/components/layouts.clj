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

(ns ai.miniforge.web-dashboard.components.layouts
  "Layout components for web dashboard.

   Layer 1: Layouts
   - page: Main page wrapper with header, nav, content
   - panel: Generic content container with header/footer
   - grid: Responsive multi-column grid layout
   - split: Two-column layout with adjustable split ratio"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Helper

(defn- build-class
  "Build CSS class string from opts and modifiers."
  [{:keys [class]} base-class & modifiers]
  (str/join " " (filter some? (concat [base-class] modifiers [class]))))

;------------------------------------------------------------------------------ Layer 1: Layouts

(defn page
  "Main page wrapper with header, nav, content, footer.

   Options:
   - :title - page title string
   - :nav - navigation hiccup content
   - :actions - action buttons hiccup content
   - :class - additional CSS classes

   Example: (page [:div \"Content\"] {:title \"Dashboard\"})"
  [content & [{:keys [title nav actions] :as opts}]]
  [:div {:class (build-class opts "page")}
   (when title
     [:header.page-header
      [:h1 title]
      (when actions [:div.page-actions actions])])
   [:div.page-body
    (when nav [:nav.page-nav nav])
    [:main.page-content content]]])

(defn panel
  "Generic content container with optional header and footer.

   Options:
   - :title - panel title string
   - :actions - header action buttons
   - :footer - footer hiccup content
   - :class - additional CSS classes

   Example: (panel [:p \"Content\"] {:title \"Panel Title\"})"
  [content & [{:keys [title actions footer] :as opts}]]
  [:div {:class (build-class opts "panel")}
   (when title
     [:div.panel-header
      [:h3 title]
      (when actions [:div.panel-actions actions])])
   [:div.panel-body content]
   (when footer [:div.panel-footer footer])])

(defn grid
  "Responsive multi-column grid layout.

   Options:
   - :columns - number of columns (default: 3)
   - :gap - :xs, :sm, :md, :lg, :xl (default: :md)
   - :class - additional CSS classes

   Example: (grid [item1 item2 item3] {:columns 3 :gap :md})"
  [items & [{:keys [columns gap] :or {columns 3 gap :md} :as opts}]]
  [:div
   {:class (build-class opts "grid" (str "gap-" (name gap)))
    :style (str "grid-template-columns: repeat(" columns ", 1fr);")}
   items])

(defn split
  "Two-column layout with adjustable split ratio.

   Options:
   - :ratio - [left right] proportions, e.g., [1 2] = 1/3 + 2/3 (default: [1 1])
   - :direction - :horizontal or :vertical (default: :horizontal)
   - :class - additional CSS classes

   Example: (split [:div \"Left\"] [:div \"Right\"] {:ratio [1 2]})"
  [left right & [{:keys [ratio direction] :or {ratio [1 1] direction :horizontal} :as opts}]]
  (let [[left-ratio right-ratio] ratio
        layout-class (str "split split-" (name direction))]
    [:div {:class (build-class opts layout-class)}
     [:div.split-left
      {:style (str "flex: " left-ratio ";")}
      left]
     [:div.split-right
      {:style (str "flex: " right-ratio ";")}
      right]]))
