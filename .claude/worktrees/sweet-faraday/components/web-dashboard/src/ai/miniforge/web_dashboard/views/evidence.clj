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

(ns ai.miniforge.web-dashboard.views.evidence
  "Evidence artifacts view for audit trails.")

;------------------------------------------------------------------------------ Layer 0
;; Evidence fragments

(defn status-badge [status]
  (let [label (if (keyword? status) (name status) (str status))]
    [:span.wf-badge {:class (str "badge-" label)} label]))

(defn workflow-evidence-item [wf]
  [:div.evidence-item
   [:div.evidence-info
    [:h4 (:workflow-name wf)]
    [:span.evidence-meta
     (status-badge (:status wf))
     (when (:completed-at wf)
       (str " completed " (:completed-at wf)))]]
   [:div.evidence-actions
    (if (:has-evidence wf)
      [:button.btn.btn-sm.btn-ghost
       {:onclick (str "location.href='/api/evidence/" (:evidence-bundle-id wf) "'")}
       "View Bundle"]
      [:span.evidence-pending "Pending"])]])

(defn train-evidence-item [train]
  [:div.evidence-item
   [:div.evidence-info
    [:h4 (:train-name train)]
    [:span.evidence-meta
     (str (:pr-count train) " PRs"
          (if (:has-evidence train) " | Evidence available" ""))]]
   (when (:has-evidence train)
     [:button.btn.btn-sm.btn-ghost
      {:onclick (str "location.href='/api/evidence/" (:evidence-bundle-id train) "'")}
      "View Bundle"])])

(defn evidence-list-fragment
  "Evidence list fragment for htmx updates.
   Accepts either a vector (legacy: train items only) or a map with :trains and :workflows."
  [state]
  (let [trains (if (map? state) (:trains state) state)
        workflows (when (map? state) (:workflows state))
        has-items (or (seq trains) (seq workflows))]
    (if-not has-items
      [:div.empty-state
       [:div.empty-icon "§"]
       [:h3 "No Evidence Bundles Yet"]
       [:p "Evidence bundles appear as workflows complete and publish artifacts."]]
      [:div.evidence-list
       (when (seq workflows)
         (list
          [:h3.evidence-section-title "Workflow Evidence"]
          (for [wf workflows]
            (workflow-evidence-item wf))))
       (when (seq trains)
         (list
          [:h3.evidence-section-title "PR Train Evidence"]
          (for [train trains]
            (train-evidence-item train))))])))

;------------------------------------------------------------------------------ Layer 1
;; Evidence view

(defn evidence-view
  "Evidence artifacts view."
  [layout state]
  (layout "Evidence"
   [:div.evidence-view
    [:div.evidence-header.aggregate-header
     [:div.evidence-title-group
      [:h2 "Evidence Bundles"]
      [:p.subtitle "Audit trail for workflows and merged PRs"]]
     [:div.pane-filter-toolbar
      [:div#filter-chips.filter-chips]
      [:div.filter-actions
       [:button.btn.btn-sm.btn-ghost.filter-add
        {:hx-get "/api/filter-fields?scope=local&pane=evidence"
         :hx-target "#filter-modal-container"
         :hx-swap "innerHTML"
         :title "Add pane-local filter"}
        "Filter"]]]]
    [:div#evidence-content
     {:hx-get "/api/evidence/list"
      :hx-trigger "refresh from:body, every 10s"
      :hx-swap "innerHTML"
      :data-filter-refresh "true"}
     (evidence-list-fragment state)]]))
