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

(ns ai.miniforge.web-dashboard.server
  "HTTP server with WebSocket support for the web dashboard."
  (:require
   [org.httpkit.server :as http]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [hiccup.core :refer [html]]
   [ai.miniforge.web-dashboard.views :as views]))

;------------------------------------------------------------------------------ Layer 0
;; Static file serving

(def content-types
  "MIME types for static files."
  {".html" "text/html"
   ".css"  "text/css"
   ".js"   "application/javascript"
   ".json" "application/json"})

(defn- get-content-type
  "Get content type from file extension."
  [path]
  (or (some (fn [[ext type]]
              (when (.endsWith path ext) type))
            content-types)
      "application/octet-stream"))

(defn- serve-static-file
  "Serve a static file from resources/public."
  [path]
  (if-let [resource (io/resource (str "public" path))]
    {:status 200
     :headers {"Content-Type" (get-content-type path)}
     :body (slurp resource)}
    {:status 404
     :headers {"Content-Type" "text/plain"}
     :body "Not Found"}))

;------------------------------------------------------------------------------ Layer 1
;; WebSocket handling and workflow state

(defn- subscribe-client!
  "Subscribe WebSocket client to event stream."
  [event-stream ch]
  (when event-stream
    (let [subscriber-id (keyword (str "ws-" (hash ch)))]
      (try
        (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
          (when es-ns
            (let [subscribe! (ns-resolve es-ns 'subscribe!)]
              (subscribe! event-stream subscriber-id
                          (fn [event]
                            (try
                              (http/send! ch (json/generate-string event))
                              (catch Exception e
                                (println "Error sending event to WebSocket:" (.getMessage e)))))))))
        (catch Exception e
          (println "Error subscribing to event stream:" (.getMessage e)))))))

(defn- unsubscribe-client!
  "Unsubscribe WebSocket client from event stream."
  [event-stream ch]
  (when event-stream
    (let [subscriber-id (keyword (str "ws-" (hash ch)))]
      (try
        (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
          (when es-ns
            (let [unsubscribe! (ns-resolve es-ns 'unsubscribe!)]
              (unsubscribe! event-stream subscriber-id))))
        (catch Exception e
          (println "Error unsubscribing from event stream:" (.getMessage e)))))))

(defn- get-workflows
  "Get current workflow state from event stream."
  [event-stream]
  (try
    (let [es-ns (find-ns 'ai.miniforge.event-stream.interface)]
      (if es-ns
        (let [get-events (ns-resolve es-ns 'get-events)
              events (get-events event-stream)]
          ;; Extract workflows from events
          ;; This is a simplified version - real impl would track workflow state
          (->> events
               (filter #(or (:workflow-id %) (:workflow/id %)))
               (group-by #(or (:workflow-id %) (:workflow/id %)))
               (map (fn [[id wf-events]]
                      (let [latest (first wf-events)]
                        {:id id
                         :name (str "Workflow " (subs (str id) 0 8))
                         :status :running
                         :phase (or (:phase latest) "unknown")
                         :progress 50
                         :agent-status "Active"})))
               (take 10)))
        []))
    (catch Exception e
      (println "Error getting workflows:" (.getMessage e))
      [])))

(defn- create-ws-handler [event-stream]
  (let [connections (atom #{})]
    (fn [req]
      (http/as-channel req
        {:on-open
         (fn [ch]
           (println "WebSocket opened")
           (swap! connections conj ch)
           (subscribe-client! event-stream ch))

         :on-close
         (fn [ch _status]
           (println "WebSocket closed")
           (swap! connections disj ch)
           (unsubscribe-client! event-stream ch))

         :on-receive
         (fn [ch data]
           (println "Received:" data)
           (http/send! ch (json/generate-string {:echo data})))}))))

;------------------------------------------------------------------------------ Layer 2
;; HTTP routes

(defn- html-response
  "Render hiccup to HTML and return as HTTP response."
  [hiccup-data]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (if (string? hiccup-data)
           hiccup-data  ; Already rendered (from views/*)
           (html hiccup-data))})  ; Render hiccup fragments for htmx

(defn- create-handler [event-stream]
  (let [ws-handler (create-ws-handler event-stream)]
    (fn [req]
      (let [uri (:uri req)]
        (cond
          ;; WebSocket
          (= uri "/ws")
          (ws-handler req)

          ;; Health check
          (= uri "/health")
          {:status 200
           :headers {"Content-Type" "application/json"}
           :body (json/generate-string {:status "ok"})}

          ;; Main views
          (= uri "/")
          (html-response (views/workflow-list (get-workflows event-stream)))

          (= uri "/evidence")
          (html-response (views/evidence-view []))

          (= uri "/artifacts")
          (html-response (views/artifacts-view []))

          (= uri "/dag")
          (html-response (views/dag-kanban-view []))

          ;; Workflow detail
          (.startsWith uri "/workflow/")
          (let [id (subs uri 10)]
            (html-response (views/workflow-detail
                            {:id id
                             :name (str "Workflow " id)
                             :phases [{:name "Spec" :status :completed}
                                      {:name "Plan" :status :running}
                                      {:name "Implement" :status :pending}]
                             :agent-output "Agent output will appear here..."})))

          ;; API endpoints for htmx
          (= uri "/api/workflows")
          (html-response
           [:div#workflow-list
            [:table.workflow-table
             [:tbody
              (map (fn [wf]
                     [:tr
                      [:td (:status wf)]
                      [:td [:a {:href (str "/workflow/" (:id wf))} (:name wf)]]
                      [:td (:phase wf)]
                      [:td (:progress wf)]
                      [:td (:agent-status wf)]])
                   (get-workflows event-stream))]]])

          ;; Static files
          (or (.startsWith uri "/css/")
              (.startsWith uri "/js/"))
          (serve-static-file uri)

          ;; 404
          :else
          {:status 404
           :headers {"Content-Type" "text/plain"}
           :body "Not Found"})))))

;------------------------------------------------------------------------------ Layer 3
;; Server lifecycle

(defn start-server!
  "Start HTTP server with WebSocket support."
  [{:keys [port event-stream] :or {port 8080}}]
  (let [handler (create-handler event-stream)
        server (http/run-server handler {:port port})
        actual-port (if (zero? port)
                      (.getLocalPort (:server-socket @server))
                      port)]
    (println (str "Web dashboard started on http://localhost:" actual-port))
    {:server server
     :port actual-port
     :event-stream event-stream}))

(defn stop-server!
  "Stop HTTP server."
  [{:keys [server]}]
  (when server
    (server :timeout 100)
    (println "Web dashboard stopped")))

(defn get-server-port
  "Get server port."
  [{:keys [port]}]
  port)
