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

(ns ai.miniforge.web-dashboard.components.data
  "Data display components for web dashboard.

   Layer 2: Data Display
   - table: Sortable, filterable table component
   - tree: Expandable hierarchical tree component
   - timeline: Chronological event list with timestamps"
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Helper

(defn- build-class
  "Build CSS class string from opts and modifiers."
  [{:keys [class]} base-class & modifiers]
  (str/join " " (filter some? (concat [base-class] modifiers [class]))))

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
