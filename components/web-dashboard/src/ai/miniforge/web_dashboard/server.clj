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
   [jsonista.core :as json]
   [clojure.java.io :as io]))

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
;; WebSocket handling

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
                              (http/send! ch (json/write-value-as-string event))
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
           (http/send! ch (json/write-value-as-string {:echo data})))}))))

;------------------------------------------------------------------------------ Layer 2
;; HTTP routes

(defn- create-handler [event-stream]
  (let [ws-handler (create-ws-handler event-stream)]
    (fn [req]
      (let [uri (:uri req)]
        (cond
          (= uri "/") (serve-static-file "/index.html")
          (= uri "/ws") (ws-handler req)
          (= uri "/health") {:status 200
                             :headers {"Content-Type" "application/json"}
                             :body (json/write-value-as-string {:status "ok"})}
          (or (.startsWith uri "/js/")
              (.startsWith uri "/css/")) (serve-static-file uri)
          :else {:status 404
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
