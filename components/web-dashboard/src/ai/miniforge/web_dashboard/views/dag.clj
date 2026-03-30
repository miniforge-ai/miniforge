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
   [clojure.string :as str]
   [ai.miniforge.web-dashboard.messages :as msg]))

;------------------------------------------------------------------------------ Layer 0
;; Filter modal (shared across panes)

(defn js-escape
  [s]
  (-> (str s)
      (str/replace "\\" "\\\\")
      (str/replace "'" "\\\\'")))

(defn option-raw-value
  [value]
  (if (keyword? value)
    (str value)
    (str value)))

(defn option-display-value
  [value]
  (if (keyword? value)
    (name value)
    (str value)))

(defn enum-option
  [filter-id scope value count cloud?]
  (let [raw-value (option-raw-value value)
        display-value (option-display-value value)]
  [:label {:class (str "filter-option" (when cloud? " filter-option-cloud"))
           :key (str filter-id "-" value)}
   [:input {:type "checkbox"
            :class "filter-checkbox"
            :name (str "filter-" (name filter-id))
            :value raw-value
            :data-filter-id (name filter-id)
            :data-scope scope
            :onchange (str "window.miniforge.filters.toggleFilter('"
                         (name filter-id) "', '" (js-escape raw-value) "', '" scope "', this.checked);")}]
   [:span (str display-value (when count (str " (" count ")")))]]))

(defn cloud-option
  [filter-id scope value count]
  (let [raw-value (option-raw-value value)
        display-value (option-display-value value)]
    [:button.filter-option-cloud-btn
     {:type "button"
      :key (str filter-id "-" value)
      :data-filter-id (name filter-id)
      :data-filter-value raw-value
      :data-scope scope
      :onclick "window.miniforge.filters.toggleCloudFilter(this);"}
     [:span.filter-option-cloud-label display-value]
     (when count
       [:span.filter-option-cloud-count count])]))

(defn dynamic-enum-options
  [filter-id scope filter-label facet-counts cloud?]
  (if (seq facet-counts)
    (for [[value count] facet-counts]
      (if cloud?
        (cloud-option filter-id scope value count)
        (enum-option filter-id scope value count cloud?)))
    (list
     [:div.filter-option-empty
      [:span "No values detected from current data."]
      [:input.filter-text-input
       {:type "text"
        :class "filter-text-input"
        :placeholder (str "Set " filter-label "...")
        :data-filter-id (name filter-id)
        :data-scope scope
        :onchange (str "window.miniforge.filters.setTextFilter('"
                     (name filter-id) "', '" scope "', this.value, ':=');")}]])))

(defn static-enum-options
  [filter-id scope values facet-counts cloud?]
  (for [value values]
    (let [option-value (if (keyword? value) (name value) (str value))]
      (if cloud?
        (cloud-option filter-id scope value (get facet-counts value))
        (enum-option filter-id scope option-value (get facet-counts value) cloud?)))))

(defn bool-filter-options
  [filter-id scope]
  [:div.filter-option
   [:label
    [:input {:type "checkbox"
             :class "filter-checkbox"
             :name (str "filter-" (name filter-id))
             :data-filter-id (name filter-id)
             :data-scope scope
             :onchange (str "window.miniforge.filters.toggleFilter('"
                          (name filter-id) "', true, '" scope "', this.checked);")}]
    [:span "Yes"]]])

(defn text-filter-input
  [filter-id filter-label scope filter-spec]
  (let [text-op (if (= :multi-path (get-in filter-spec [:filter/value :kind]))
                  ":text-search"
                  ":contains")]
    [:input.filter-text-input
     {:type "text"
      :class "filter-text-input"
      :placeholder (str "Search " filter-label "...")
      :data-filter-id (name filter-id)
      :data-scope scope
      :onchange (str "window.miniforge.filters.setTextFilter('"
                   (name filter-id) "', '" scope "', this.value, '" text-op "');")}]))

(defn filter-options-fragment
  [filter-spec scope facet-counts cloud?]
  (let [filter-id (:filter/id filter-spec)
        filter-label (:filter/label filter-spec)
        filter-type (:filter/type filter-spec)]
    (case filter-type
      :enum
      (if (= :dynamic (:filter/values filter-spec))
        (dynamic-enum-options filter-id scope filter-label facet-counts cloud?)
        (static-enum-options filter-id scope (:filter/values filter-spec) facet-counts cloud?))

      :bool
      (bool-filter-options filter-id scope)

      :text
      (text-filter-input filter-id filter-label scope filter-spec)

      [:span "Unsupported filter type: " (name filter-type)])))

(defn filter-section-fragment
  [filter-spec facets scope]
  (let [filter-id (:filter/id filter-spec)
        filter-label (:filter/label filter-spec)
        filter-type (:filter/type filter-spec)
        facet-counts (get facets filter-id)
        cloud? (and (= scope "global") (= filter-type :enum))
        section-class (str "filter-section"
                           (when (= scope "global") " filter-section-global-compact")
                           (when (= scope "local") " filter-section-local-compact"))]
    [:div {:class section-class
           :key (str filter-id)}
     [:h4.filter-section-title filter-label]
     [:div {:class (str "filter-options" (when cloud? " filter-options-cloud"))}
      (filter-options-fragment filter-spec scope facet-counts cloud?)]]))

(defn filter-modal-body-fragment
  [filters facets scope]
  (if (empty? filters)
    [:p.empty-message (if (= scope "global")
                        "No global filter fields configured."
                        "No filters available for this pane.")]
    (for [filter-spec filters]
      (filter-section-fragment filter-spec facets scope))))

(defn filter-modal-fragment
  "Filter selection modal content with faceted counts."
  [{:keys [filters facets scope _pane] :or {filters [] facets {} scope "local"}}]
  [:div.filter-modal
   [:div.filter-modal-overlay {:onclick "this.parentElement.remove()"}]
   [:div.filter-modal-content
    [:div.filter-modal-header
     [:h3
      (if (= scope "global")
        "Global Filters"
        "Filters")]
     [:div.scope-badge {:class (if (= scope "global") "scope-badge-global" "scope-badge-local")}
      (if (= scope "global")
        "Applies to all panes"
        "This pane only")]
     [:button.filter-modal-close
      {:onclick "this.parentElement.parentElement.parentElement.remove()"
       :title (msg/t :action/close)}
      "×"]]
    [:div.filter-modal-body
     (filter-modal-body-fragment filters facets scope)]
    [:div.filter-modal-footer
     [:button.btn.btn-sm.btn-ghost
      {:onclick "this.parentElement.parentElement.parentElement.remove()"}
      (msg/t :action/done)]]]])

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
             [:div.pane-filter-toolbar
              [:div#filter-chips.filter-chips
               ;; Active filter chips will be added here dynamically via JavaScript
               ]
              [:div.filter-actions
               [:button.btn.btn-sm.btn-ghost.filter-add
                {:hx-get "/api/filter-fields?scope=local&pane=task-status"
                 :hx-target "#filter-modal-container"
                 :hx-swap "innerHTML"
                 :title "Add pane-local filter"}
                (msg/t :action/filter)]]]]]
           [:div.kanban-board
            (for [status [:blocked :ready :running :done]]
              [:div.kanban-column {:class (name status)}
               [:div.column-header
                [:h3
                 {:class "column-filter-label"
                  :title (str "Filter globally by status: " (name status))
                  :onclick (str "window.miniforge.filters.addFilter('entity-status', ':=', '" (name status) "', 'global');")}
                 (str/upper-case (name status))]
                [:span.column-count
                 (count (filter #(= status (:status %)) (:tasks state)))]]
               [:div.column-content
                (for [task (filter #(= status (:status %)) (:tasks state))]
                  (let [repo (:repo task)
                        repo-js (str/replace (str repo) "'" "\\\\'")]
                    [:div.kanban-card
                     [:div.card-header
                      [:span.card-id
                       [:button.card-meta-filter
                        {:title (str "Filter globally by repository: " repo)
                         :onclick (str "event.stopPropagation(); window.miniforge.filters.addFilter('repository', ':=', '" repo-js "', 'global');")}
                        repo]
                       (str " #" (:id task))]
                      (when (seq (:dependencies task))
                        [:span.card-deps (str "↓ " (count (:dependencies task)))])]
                     [:div.card-title (:title task)]
                     [:div.card-footer
                      [:a.card-link {:href (str "/train/" (:train-id task))} "View Train →"]]]))]])]]))
