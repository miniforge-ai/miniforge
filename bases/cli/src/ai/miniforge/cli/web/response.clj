(ns ai.miniforge.cli.web.response
  "HTTP response constructors.")

(defn html [body]
  {:status 200
   :headers {"Content-Type" "text/html; charset=utf-8"}
   :body (str body)})

(defn not-found [msg]
  {:status 404 :body msg})

(defn bad-request [msg]
  {:status 400 :body msg})

(defn sse-headers []
  {:status 200
   :headers {"Content-Type" "text/event-stream"
             "Cache-Control" "no-cache"
             "Connection" "keep-alive"
             "Access-Control-Allow-Origin" "*"}})
