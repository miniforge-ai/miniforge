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

(ns ai.miniforge.web-dashboard.components
  "Reusable Hiccup component library for web dashboard.

   Components are pure functions that return Hiccup data structures.
   Each component follows a consistent pattern: (component content & [opts])

   Layers:
   - Layer 0: Primitives (button, badge, card, stat-card, progress-bar, status-dot, icon)
   - Layer 1: Layouts (page, panel, grid, split)
   - Layer 2: Data Display (table, tree, timeline)"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Helper

(defn- build-class
  "Build CSS class string from opts and modifiers."
  [{:keys [class]} base-class & modifiers]
  (str/join " " (filter some? (concat [base-class] modifiers [class]))))

;------------------------------------------------------------------------------ Layer 0: Primitives

(defn button
  "Button component with variants and sizes.

   Options:
   - :variant - :primary, :secondary, :danger, :ghost (default: :primary)
   - :size - :sm, :md, :lg (default: :md)
   - :disabled - boolean (default: false)
   - :class - additional CSS classes

   Example: (button \"Approve\" {:variant :primary :size :md})"
  [label & [{:keys [variant size disabled] :or {variant :primary size :md} :as opts}]]
  [:button
   {:class (build-class opts "btn" (str "btn-" (name variant)) (str "btn-" (name size)))
    :disabled disabled}
   label])

(defn badge
  "Badge component for labels and status indicators.

   Options:
   - :variant - :success, :warning, :error, :info, :neutral (default: :neutral)
   - :icon - optional icon string to display before label
   - :class - additional CSS classes

   Example: (badge \"HIGH\" {:variant :error :icon \"🔴\"})"
  [label & [{:keys [variant icon] :or {variant :neutral} :as opts}]]
  [:span
   {:class (build-class opts "badge" (str "badge-" (name variant)))}
   (when icon [:span.badge-icon icon])
   label])

(defn card
  "Card component with optional title and footer.

   Options:
   - :title - card title string
   - :footer - footer hiccup content
   - :class - additional CSS classes

   Example: (card [:p \"Content\"] {:title \"Card Title\"})"
  [content & [{:keys [title footer] :as opts}]]
  [:div {:class (build-class opts "card")}
   (when title [:div.card-header title])
   [:div.card-body content]
   (when footer [:div.card-footer footer])])

(defn stat-card
  "Metric card for dashboard with large value and label.

   Options:
   - :trend - :up, :down, :neutral (optional)
   - :class - additional CSS classes

   Example: (stat-card \"47\" \"PRs\" {:trend :up})"
  [value label & [{:keys [trend] :as opts}]]
  [:div {:class (build-class opts "stat-card")}
   [:div.stat-value value]
   [:div.stat-label label]
   (when trend
     [:div.stat-trend {:class (name trend)}
      (case trend
        :up "↑"
        :down "↓"
        :neutral "→"
        "")])])

(defn progress-bar
  "Progress bar component showing completion percentage.

   Options:
   - :show-label - show percentage label (default: false)
   - :variant - :primary, :success, :warning, :error (default: :primary)
   - :class - additional CSS classes

   Example: (progress-bar 70 {:show-label true})"
  [percent & [{:keys [show-label variant] :or {variant :primary} :as opts}]]
  [:div {:class (build-class opts "progress")}
   [:div.progress-fill
    {:style (str "width: " percent "%")
     :class (str "progress-" (name variant))}]
   (when show-label [:div.progress-label (str percent "%")])])

(defn status-dot
  "Colored status indicator dot.

   Statuses: :running, :completed, :failed, :pending, :blocked

   Example: (status-dot :running)"
  [status]
  [:span {:class (str "status-dot " (name status))} "●"])

(defn icon
  "Unicode icon component with optional label.

   Icons: :check, :cross, :clock, :dot, :circle, :arrow-right, :arrow-down, :arrow-right-small

   Options:
   - :class - additional CSS classes

   Example: (icon :check {:class \"success\"})"
  [name & [{:keys [] :as opts}]]
  (let [icons {:check "✓" :cross "✗" :clock "⏳" :dot "●" :circle "○"
               :arrow-right "→" :arrow-down "▼" :arrow-right-small "▸"}]
    [:span {:class (build-class opts "icon")} (get icons name "")]))

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

;------------------------------------------------------------------------------ Layer 2: Data Display

(defn table
  "Sortable, filterable table component.

   Options:
   - :columns - [{:key :name :label \"Name\" :align :left}]
   - :sortable - enable sorting (default: false)
   - :selectable - enable row selection (default: false)
   - :class - additional CSS classes

   Example: (table [{:id 1 :name \"Alice\"}] {:columns [{:key :name :label \"Name\"}]})"
  [rows & [{:keys [columns sortable selectable] :as opts}]]
  [:table {:class (build-class opts "table")}
   [:thead
    [:tr
     (when selectable [:th])
     (for [{:keys [key label align] :or {align :left}} columns]
       [:th {:class (when sortable "sortable")
             :data-key (name key)
             :style (str "text-align: " (name align) ";")}
        label])]]
   [:tbody
    (for [row rows]
      [:tr
       (when selectable [:td [:input {:type "checkbox"}]])
       (for [{:keys [key align] :or {align :left}} columns]
         [:td {:style (str "text-align: " (name align) ";")}
          (get row key)])])]])

(defn tree
  "Expandable hierarchical tree component.

   Options:
   - :expanded - set of expanded node IDs (default: #{})
   - :selected - selected node ID (optional)
   - :icon-fn - function to get icon for node (default: returns \"▸\")
   - :class - additional CSS classes

   Each node: {:id any :label string :children [nodes]}

   Example: (tree [{:id 1 :label \"Root\" :children [...]}] {:expanded #{1}})"
  [nodes & [{:keys [expanded selected icon-fn] :or {expanded #{} icon-fn (constantly "▸")} :as opts}]]
  (letfn [(render-node [{:keys [id label children] :as node}]
            (let [has-children? (seq children)
                  is-expanded? (contains? expanded id)
                  is-selected? (= id selected)]
              [:div.tree-node
               [:div.tree-node-label
                {:class (str (when is-selected? "selected ")
                             (when has-children? "has-children"))}
                [:span.tree-icon (icon-fn node)]
                [:span label]]
               (when (and has-children? is-expanded?)
                 [:div.tree-children
                  (map render-node children)])]))]
    [:div {:class (build-class opts "tree")}
     (map render-node nodes)]))

(defn timeline
  "Chronological event list with timestamps.

   Options:
   - :show-relative-time - show relative time (e.g., \"2h ago\") (default: false)
   - :class - additional CSS classes

   Each event: {:timestamp inst :label string :icon string}

   Example: (timeline [{:timestamp #inst \"2025-02-08\" :label \"Event\" :icon \"●\"}])"
  [events & [{:keys [] :as opts}]]
  [:div {:class (build-class opts "timeline")}
   (for [{:keys [timestamp label icon]} events]
     [:div.timeline-event
      [:div.timeline-marker
       [:span.timeline-icon (or icon "●")]]
      [:div.timeline-content
       [:div.timeline-label label]
       [:div.timeline-time (str timestamp)]]])])
