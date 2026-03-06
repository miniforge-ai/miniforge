(ns ai.miniforge.web-dashboard.views.archived
  "Archived workflow list and detail views."
  (:require
   [hiccup2.core :refer [html]]
   [ai.miniforge.web-dashboard.components :as c]))

;------------------------------------------------------------------------------ Layer 0
;; Pure helpers

(defn format-date
  "Format timestamp as full date+time for historical entries."
  [ts]
  (let [date (cond
               (instance? java.util.Date ts) ts
               (instance? java.time.Instant ts) (java.util.Date/from ts)
               (string? ts) (try
                              (java.util.Date/from (java.time.Instant/parse ts))
                              (catch Exception _ nil))
               :else nil)]
    (if date
      (.format (java.text.SimpleDateFormat. "yyyy-MM-dd HH:mm") date)
      "—")))

(defn format-file-size
  "Format bytes to human-readable size."
  [bytes]
  (cond
    (nil? bytes)              "—"
    (< bytes 1024)            (str bytes " B")
    (< bytes (* 1024 1024))   (str (quot bytes 1024) " KB")
    :else                     (format "%.1f MB" (/ bytes 1024.0 1024.0))))

(defn status-label
  [status]
  (case status
    :running   "Running"
    :completed "Completed"
    :failed    "Failed"
    :stale     "Stale"
    "Unknown"))

;------------------------------------------------------------------------------ Layer 1
;; Fragments

(defn archived-workflow-list-fragment
  "Archived workflow list fragment — expandable cards with on-demand event loading."
  [archived-workflows loading?]
  (html
   (cond
     loading?
     [:div.loading-spinner "Scanning archive..."]

     (empty? archived-workflows)
     [:div.empty-state [:p "No archived workflows"]]

     :else
     [:div.workflow-card-list
      (for [wf archived-workflows]
        (let [wf-id  (str (:id wf))
              status (get wf :status :stale)]
          [:details.workflow-card.archived-card
           {:id (str "arch-" wf-id)}
           [:summary.workflow-card-summary
            [:span.wf-status-dot (c/status-dot status)]
            [:span.wf-name (:name wf)]
            [:span.wf-badge {:class (str "badge-" (name status))}
             (status-label status)]
            [:span.wf-phase (or (some-> (:phase wf) name) "—")]
            [:span.wf-date (format-date (:started-at wf))]
            [:span.wf-size (format-file-size (:file-size wf))]
            [:span.wf-expand-icon "▸"]]
           [:div.workflow-card-body
            {:id (str "arch-panel-" wf-id)
             :hx-get (str "/api/archive/" wf-id "/events")
             :hx-trigger "toggle once from:closest details"
             :hx-swap "innerHTML"}
            [:div.loading-spinner "Loading events..."]]
           [:div.workflow-card-actions
            [:button.btn.btn-sm.btn-ghost.btn-danger
             {:hx-post (str "/api/archive/" wf-id "/delete")
              :hx-confirm "Delete this archived workflow?"
              :hx-target (str "#arch-" wf-id)
              :hx-swap "outerHTML"}
             "Delete"]]]))])))
