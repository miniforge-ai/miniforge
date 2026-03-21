(ns ai.miniforge.cli.web.response
  "HTTP response constructors."
  (:require
   [ai.miniforge.response.interface :as anomaly]))

(defn html [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str body)})

(defn not-found [msg]
  {:status 404 :body msg})

(defn bad-request [msg]
  {:status 400 :body msg})

(defn from-anomaly
  "Create an HTTP response from a canonical anomaly map.
   Uses the boundary translator — only call at HTTP edges."
  [anomaly-map]
  (anomaly/anomaly->http-response anomaly-map))

(defn sse-headers []
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"
             "Access-Control-Allow-Origin" "*"}})
