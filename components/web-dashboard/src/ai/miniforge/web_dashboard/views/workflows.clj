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

(ns ai.miniforge.web-dashboard.views.workflows
  "Workflow list and detail views."
  (:require
   [clojure.string :as str]
   [hiccup2.core :refer [html]]
   [ai.miniforge.web-dashboard.components :as c]
   [ai.miniforge.web-dashboard.messages :as msg]))

;; Duration thresholds — not locale-dependent (math, not translation)
(def ^:private ms-per-second 1000)
(def ^:private ms-per-minute 60000)

;------------------------------------------------------------------------------ Layer 0
;; Workflow fragments

(defn format-time
  [ts]
  (let [date (cond
               (instance? java.util.Date ts) ts
               (instance? java.time.Instant ts) (java.util.Date/from ts)
               (string? ts) (try
                              (java.util.Date/from (java.time.Instant/parse ts))
                              (catch Exception _ nil))
               :else nil)]
    (if date
      (.format (java.text.SimpleDateFormat. (msg/t :time/format)) date)
      (msg/t :time/none))))

(defn format-duration-ms
  [duration-ms]
  (when (number? duration-ms)
    (cond
      (< duration-ms ms-per-second) (str duration-ms (msg/t :duration/ms-suffix))
      (< duration-ms ms-per-minute) (format "%.1f%s" (/ duration-ms (double ms-per-second))
                                            (msg/t :duration/s-suffix))
      :else                         (format "%.1f%s" (/ duration-ms (double ms-per-minute))
                                            (msg/t :duration/min-suffix)))))

(defn format-cost
  [cost-usd]
  (when (number? cost-usd)
    (format "$%.4f" (double cost-usd))))

(defn event-message
  "Return the best human-readable message string for an event, or nil.

  The event schema uses different fields depending on event type:
  - :agent/chunk events carry streaming text in :chunk/delta (not :event/message).
    We trim whitespace and return nil for blank deltas so the UI skips empty chunks.
  - All other events use :event/message (canonical field) or the legacy :message
    key that predates the namespaced schema — both are checked to handle old events
    already persisted to disk.
  - :workflow/failed events additionally carry a :workflow/failure-reason that has
    no corresponding :event/message entry; we surface it with a translated prefix."
  [evt]
  (if (= :agent/chunk (:event/type evt))
    ;; Streaming chunks: text lives in :chunk/delta, not :event/message
    (some-> (:chunk/delta evt) str/trim not-empty)
    (or (:message evt)                          ; legacy un-namespaced key (older persisted events)
        (:event/message evt)                    ; canonical namespaced key
        (when-let [reason (:workflow/failure-reason evt)]
          ;; failure events don't set :event/message — surface reason with i18n prefix
          (str (msg/t :metric/failure-prefix) reason)))))

(defn- coalesce-metric
  "Return the first non-nil metric value across phase and workflow namespaces."
  [evt phase-key wf-key]
  (or (get evt phase-key) (get evt wf-key)))

(defn event-metric-items
  "Render compact per-event metrics as a seq of display strings."
  [evt]
  (let [duration-ms (coalesce-metric evt :phase/duration-ms :workflow/duration-ms)
        tokens      (coalesce-metric evt :phase/tokens      :workflow/tokens)
        cost-usd    (coalesce-metric evt :phase/cost-usd    :workflow/cost-usd)]
    (cond-> []
      (:phase/outcome evt) (conj (str (msg/t :metric/outcome-prefix) (name (:phase/outcome evt))))
      duration-ms          (conj (format-duration-ms duration-ms))
      tokens               (conj (str tokens (msg/t :metric/tokens-suffix)))
      cost-usd             (conj (format-cost cost-usd)))))

(defn workflow-events-fragment
  "Event list fragment for htmx updates."
  [events]
  (html
   (if (empty? events)
     [:div.empty-state [:p "No events yet"]]
     [:div.event-list
      (for [evt (take 200 events)]
        (let [evt-type    (get evt :event/type "unknown")
              evt-ts      (:event/timestamp evt)
              evt-phase   (or (:workflow/phase evt) (:phase evt))
              type-name   (if (keyword? evt-type) (name evt-type) (str evt-type))
              metric-items (event-metric-items evt)]
          [:div.event-item {:class (str "event-" (str/replace type-name #"/" "-"))}
           [:span.event-time (format-time evt-ts)]
           [:span.event-type type-name]
           (when evt-phase
             [:span.event-phase (str evt-phase)])
           (when-let [msg (event-message evt)]
             [:span.event-message msg])
           (when (seq metric-items)
             [:span.event-metrics (str/join " • " metric-items)])]))])))

(defn status-label
  "Human-readable status label."
  [status]
  (msg/t (keyword "workflow.status" (name (or status :unknown)))))

(defn- workflow-empty-state
  "Empty-state placeholder shown when there are no workflow runs."
  []
  [:div.empty-state
   [:div.empty-icon "⚙️"]
   [:h3 (msg/t :workflow/none-heading)]
   [:p (msg/t :workflow/none-body)]
   [:code.empty-code "miniforge workflow run examples/workflows/simple.edn"]
   [:p.empty-hint (msg/t :workflow/none-hint)]])

(defn- workflow-card-summary
  "The [:summary ...] element for a single workflow card."
  [wf status]
  [:summary.workflow-card-summary
   [:span.wf-status-dot (c/status-dot status)]
   [:span.wf-name (:name wf)]
   [:span.wf-badge {:class (str "badge-" (name status))}
    (status-label status)]
   [:span.wf-phase (or (some-> (:phase wf) name) (msg/t :time/none))]
   [:div.wf-progress-inline
    [:div.wf-progress-track
     [:div.wf-progress-fill
      {:style (str "width:" (get wf :progress 0) "%")}]]]
   [:span.wf-time (format-time (:started-at wf))]
   [:span.wf-expand-icon "▸"]])

(defn- workflow-card
  "One full [:details ...] card for a single workflow."
  [wf]
  (let [wf-id  (str (:id wf))
        status (get wf :status :unknown)]
    [:details.workflow-card
     {:id                wf-id
      :data-workflow-id  wf-id
      :hx-get            (str "/api/workflow/" wf-id "/panel")
      :hx-trigger        "toggle once"
      :hx-target         (str "#wf-panel-" wf-id)
      :hx-swap           "innerHTML"}
     (workflow-card-summary wf status)
     [:div.workflow-card-body
      {:id               (str "wf-panel-" wf-id)
       :data-workflow-id wf-id}]]))

(defn workflow-list-fragment
  "Workflow list fragment — expandable cards."
  [workflows]
  (html
   (if (empty? workflows)
     (workflow-empty-state)
     [:div.workflow-card-list (map workflow-card workflows)])))

(defn- workflow-metric-fields
  "Return a seq of [css-class label value-string] tuples for non-nil metric fields.
   Used by workflow-panel-meta to render each metric span generically."
  [workflow]
  (cond-> []
    (get-in workflow [:metrics :tokens])
    (conj [:workflow-tokens "Tokens: " (str (get-in workflow [:metrics :tokens]))])

    (format-duration-ms (get-in workflow [:metrics :duration-ms]))
    (conj [:workflow-duration "Duration: " (format-duration-ms (get-in workflow [:metrics :duration-ms]))])

    (format-cost (get-in workflow [:metrics :cost-usd]))
    (conj [:workflow-cost "Cost: " (format-cost (get-in workflow [:metrics :cost-usd]))])

    (:started-at workflow)
    (conj [:workflow-started "Started: " (format-time (:started-at workflow))])))

(defn- workflow-panel-controls
  "Pause/resume/stop buttons — only rendered for running workflows."
  [workflow-id]
  [:div.workflow-panel-controls
   [:button.btn.btn-sm
    {:onclick (str "window.miniforge.postWorkflowCommand('" workflow-id "','pause')")
     :title "Pause"}
    "Pause"]
   [:button.btn.btn-sm
    {:onclick (str "window.miniforge.postWorkflowCommand('" workflow-id "','resume')")
     :title "Resume"}
    "Resume"]
   [:button.btn.btn-sm.btn-danger
    {:onclick (str "window.miniforge.postWorkflowCommand('" workflow-id "','stop')")
     :title "Stop"}
    "Stop"]])

(defn- workflow-panel-meta
  "Meta row: status badge, phase, progress, and any available metric fields."
  [workflow]
  [:div.workflow-panel-meta
   [:span (c/badge (name (get workflow :status :unknown))
                   {:variant (case (:status workflow)
                               :completed :success
                               :running   :info
                               :failed    :error
                               :stale     :warning
                               :neutral)})]
   [:span.workflow-phase    (str "Phase: "    (get workflow :phase (msg/t :time/none)))]
   [:span.workflow-progress (str "Progress: " (get workflow :progress 0) "%")]
   (for [[css-class label value] (workflow-metric-fields workflow)]
     [:span {:class (name css-class)} (str label value)])])

(defn workflow-detail-panel
  "Inline detail panel loaded on card expand."
  [workflow events]
  (html
   [:div.workflow-panel
    {:data-workflow-id (str (:id workflow))}
    (workflow-panel-meta workflow)

    (when-let [preview (:latest-output workflow)]
      [:div.workflow-panel-output
       [:h4.section-title "Latest Output"]
       [:pre.workflow-stream-preview preview]])

    ;; Controls — only for running workflows
    (when (= :running (:status workflow))
      (workflow-panel-controls (:id workflow)))

    ;; Event timeline
    [:div.workflow-panel-events
     [:h4.section-title "Event Timeline"]
     [:div {:id               (str "wf-events-" (:id workflow))
            :data-workflow-id (str (:id workflow))
            :hx-get           (str "/api/workflow/" (:id workflow) "/events")
            :hx-trigger       "refresh from:body, every 5s"
            :hx-swap          "innerHTML"}
      (workflow-events-fragment events)]]]))

;------------------------------------------------------------------------------ Layer 1
;; Page views

(defn workflows-view
  "Workflows list page view."
  [layout workflows]
  (layout "Workflows"
   [:div.workflows-page
    [:section.workflows-section
     [:div.workflows-header.aggregate-header
      [:div.workflows-title-group
       [:h2 "Workflow Runs"]
       [:p.subtitle "Live execution history and status across active workflows"]
       [:span.workflows-count
        (str (count workflows) " " (if (= 1 (count workflows)) "workflow" "workflows"))]]
      [:div.pane-filter-toolbar
       [:div#filter-chips.filter-chips]
       [:div.filter-actions
        [:button.btn.btn-sm.btn-ghost.filter-add
         {:hx-get "/api/filter-fields?scope=local&pane=workflows"
          :hx-target "#filter-modal-container"
          :hx-swap "innerHTML"
          :title "Add pane-local filter"}
         (msg/t :action/filter)]]]]
     ;; Refresh on WebSocket push, skip if any card is expanded (preserves open state)
     [:div#workflows-content
      {:hx-get "/api/workflows"
       :hx-trigger "refresh[!document.querySelector('.workflow-card[open]')] from:body"
       :hx-swap "innerHTML"}
      (workflow-list-fragment workflows)]]

    ;; Archived section — loads in background, static data
    [:section.archived-section
     [:div.archived-header
      [:h3 "Archived Workflows"]
      [:button.btn.btn-sm.btn-ghost
       {:hx-post "/api/archive/retention"
        :hx-vals "{\"max_age_days\": 30}"
        :hx-confirm "Delete archived workflows older than 30 days?"
        :hx-target "#archived-content"
        :hx-swap "innerHTML"}
       "Clean up (30d+)"]]
     [:div#archived-content
      {:hx-get "/api/archived-workflows"
       :hx-trigger "load"
       :hx-swap "innerHTML"}
      [:div.loading-spinner "Scanning archive..."]]]]))

;------------------------------------------------------------------------------ Layer 2
;; Workflow detail view (direct URL fallback)

(defn workflow-detail-view
  "Detailed workflow view for direct URL access. Redirects to workflows page."
  [layout workflow events]
  (if (:error workflow)
    (layout (msg/t :status/error) [:div.error (:error workflow)])
    (layout (str "Workflow: " (:name workflow))
     [:div.workflow-detail
      [:div.workflow-header
       [:a.back-link {:href "/workflows"} "← Back to Workflows"]
       [:h2 (:name workflow)]]
      [:div#workflow-detail-panel
       {:data-workflow-id (str (:id workflow))}
       (workflow-detail-panel workflow events)]])))
