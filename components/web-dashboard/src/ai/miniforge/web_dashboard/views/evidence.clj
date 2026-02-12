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
;; Evidence view

(defn evidence-view
  "Evidence artifacts view."
  [layout state]
  (layout "Evidence"
   [:div.evidence-view
    [:div.evidence-header
     [:h2 "Evidence Bundles"]
     [:p.subtitle "Audit trail for all merged PRs"]
     ;; Local filter bar for this pane
     [:div.evidence-filter-bar
      [:div#filter-chips.filter-chips]
      [:div.filter-actions
       [:button.btn.btn-sm.btn-ghost.filter-add
        {:hx-get "/api/filter-fields?scope=local&pane=evidence"
         :hx-target "#filter-modal-container"
         :hx-swap "innerHTML"
         :title "Add pane-local filter"}
        "+ Filter"]]]]
    ;; Filter modal container
    [:div#filter-modal-container]
    [:div.evidence-list
     (for [train (:trains state)]
       [:div.evidence-item
        [:div.evidence-info
         [:h4 (:train-name train)]
         [:span.evidence-meta
          (str (:pr-count train) " PRs • "
               (if (:has-evidence train) "Evidence available" "No evidence"))]]
        (when (:has-evidence train)
          [:button.btn.btn-sm.btn-ghost
           {:onclick (str "location.href='/api/evidence/" (:evidence-bundle-id train) "'")}
           "View Bundle →"])])]]))
