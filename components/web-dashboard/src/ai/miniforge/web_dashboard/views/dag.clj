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

(ns ai.miniforge.web-dashboard.views.dag
  "DAG kanban board views for workflow task visualization."
  (:require
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0
;; Filter modal (shared across panes)

(defn filter-modal-fragment
  "Filter selection modal content with faceted counts."
  [{:keys [filters facets scope _pane] :or {filters [] facets {} scope "local"}}]
  [:div.filter-modal
   [:div.filter-modal-overlay {:onclick "this.parentElement.remove()"}]
   [:div.filter-modal-content
    [:div.filter-modal-header
     [:h3
      (if (= scope "global")
        [:span [:span.scope-icon "🌐"] " Add Global Filter"]
        [:span [:span.scope-icon "📊"] " Add Pane Filter"])]
     [:div.scope-badge {:class (if (= scope "global") "scope-badge-global" "scope-badge-local")}
      (if (= scope "global")
        "Applies to all panes"
        "This pane only")]
     [:button.filter-modal-close
      {:onclick "this.parentElement.parentElement.parentElement.remove()"
       :title "Close"}
      "×"]]
    [:div.filter-modal-body
     (if (empty? filters)
       [:p.empty-message "No filters available for this pane."]
       (for [filter-spec filters]
         (let [filter-id (:filter/id filter-spec)
               filter-label (:filter/label filter-spec)
               filter-type (:filter/type filter-spec)
               facet-counts (get facets filter-id)]
           [:div.filter-section {:key (str filter-id)}
            [:h4.filter-section-title filter-label]
            [:div.filter-options
             (case filter-type
               :enum
               ;; Show options with counts
               (if (= :dynamic (:filter/values filter-spec))
                 ;; Dynamic values from facets
                 (for [[value count] facet-counts]
                   [:label.filter-option {:key (str filter-id "-" value)}
                    [:input {:type "checkbox"
                             :class "filter-checkbox"
                             :name (str "filter-" (name filter-id))
                             :value (str value)
                             :data-filter-id (name filter-id)
                             :data-scope scope
                             :onchange (str "window.miniforge.filters.toggleFilter('"
                                          (name filter-id) "', '" value "', '" scope "', this.checked);")}]
                    [:span (str value (when count (str " (" count ")")))]
                    ])
                 ;; Static values
                 (for [value (:filter/values filter-spec)]
                   (let [count (get facet-counts value)]
                     [:label.filter-option {:key (str filter-id "-" value)}
                      [:input {:type "checkbox"
                               :class "filter-checkbox"
                               :name (str "filter-" (name filter-id))
                               :value (name value)
                               :data-filter-id (name filter-id)
                               :data-scope scope
                               :onchange (str "window.miniforge.filters.toggleFilter('"
                                            (name filter-id) "', '" (name value) "', '" scope "', this.checked);")}]
                      [:span (str (name value) (when count (str " (" count ")")))]])))

               :bool
               [:div.filter-option
                [:label
                 [:input {:type "checkbox"
                          :class "filter-checkbox"
                          :name (str "filter-" (name filter-id))
                          :data-filter-id (name filter-id)
                          :data-scope scope
                          :onchange (str "window.miniforge.filters.toggleFilter('"
                                       (name filter-id) "', 'true', '" scope "', this.checked);")}]
                 [:span "Yes"]]]

               :text
               [:input.filter-text-input
                {:type "text"
                 :class "filter-text-input"
                 :placeholder (str "Search " filter-label "...")
                 :data-filter-id (name filter-id)
                 :data-scope scope
                 :onchange (str "if(this.value) { "
                              "window.miniforge.filters.toggleFilter('"
                              (name filter-id) "', this.value, '" scope "', true); "
                              "}")}]

               ;; Default
               [:span "Unsupported filter type: " (name filter-type)])]])))]
    [:div.filter-modal-footer
     [:button.btn.btn-sm.btn-ghost
      {:onclick "this.parentElement.parentElement.parentElement.remove()"}
      "Done"]]]])

;------------------------------------------------------------------------------ Layer 1
;; DAG kanban view

(defn dag-kanban-view
  "Task status kanban board for workflow visualization."
  [layout state]
  (layout "Task Status"
   [:div.kanban-view
    [:div.kanban-header
     [:div.kanban-title-row
      [:h2 "Workflow Tasks"]
      [:div.kanban-filter-bar
       [:div#filter-chips.filter-chips
        ;; Active filter chips will be added here dynamically via JavaScript]
       [:div.filter-actions
        [:button.btn.btn-sm.btn-ghost.filter-add
         {:hx-get "/api/filter-fields?scope=local&pane=task-status"
          :hx-target "#filter-modal-container"
          :hx-swap "innerHTML"
          :title "Add pane-local filter"}
         "+ Filter"]]]]]
    ;; Filter modal container
    [:div#filter-modal-container]
    [:div.kanban-board
     (for [status [:blocked :ready :running :done]]
       [:div.kanban-column {:class (name status)}
        [:div.column-header
         [:h3 (str/upper-case (name status))]
         [:span.column-count
          (count (filter #(= status (:status %)) (:tasks state)))]]
        [:div.column-content
         (for [task (filter #(= status (:status %)) (:tasks state))]
           [:div.kanban-card
            [:div.card-header
             [:span.card-id (str (:repo task) " #" (:id task))]
             (when (seq (:dependencies task))
               [:span.card-deps (str "↓ " (count (:dependencies task)))])]
            [:div.card-title (:title task)]
            [:div.card-footer
             [:a.card-link {:href (str "/train/" (:train-id task))} "View Train →"]]])]])]]]))
