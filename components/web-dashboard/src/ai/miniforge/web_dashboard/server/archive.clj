(ns ai.miniforge.web-dashboard.server.archive
  "HTTP handlers for archived workflow endpoints."
  (:require
   [cheshire.core :as json]
   [ai.miniforge.web-dashboard.server.responses :as responses]
   [ai.miniforge.web-dashboard.views :as views]
   [ai.miniforge.web-dashboard.state :as state]))

;------------------------------------------------------------------------------ Layer 0
;; Archive API handlers

(defn handle-archived-workflows
  "GET /api/archived-workflows — archived workflow list fragment."
  [state _params]
  (let [archived (state/get-archived-workflows state)
        loading? (state/archive-loading? state)]
    (responses/html-response (views/archived-workflow-list-fragment archived loading?))))

(defn handle-archived-workflow-events
  "GET /api/archive/{id}/events — load full events on-demand."
  [state workflow-id]
  (responses/html-response
   (views/workflow-events-fragment
    (state/get-archived-workflow-events state workflow-id))))

;------------------------------------------------------------------------------ Layer 1
;; Mutation handlers

(defn handle-delete
  "POST /api/archive/{id}/delete — delete an archived workflow and its file."
  [state workflow-id]
  (state/delete-archived-workflow! state workflow-id)
  (responses/json-response {:status "deleted"}))

(defn handle-retention
  "POST /api/archive/retention — apply retention policy."
  [state body]
  (try
    (let [data         (json/parse-string body true)
          max-age-days (or (:max_age_days data) (:max-age-days data) 30)]
      (state/apply-retention! state max-age-days)
      (let [archived (state/get-archived-workflows state)
            loading? (state/archive-loading? state)]
        (responses/html-response (views/archived-workflow-list-fragment archived loading?))))
    (catch Exception e
      {:status 400
       :headers {"Content-Type" "application/json"}
       :body (json/generate-string {:error (str "Bad request: " (.getMessage e))})})))
