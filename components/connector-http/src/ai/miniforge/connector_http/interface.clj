(ns ai.miniforge.connector-http.interface
  "Public API for the HTTP connector component."
  (:require [ai.miniforge.connector-http.core :as core]
            [ai.miniforge.connector-http.cursors :as cursors]
            [ai.miniforge.connector-http.rate-limit :as rate-limit]
            [ai.miniforge.connector-http.request :as request]
            [ai.miniforge.connector.interface :as conn]))

(defn create-http-connector
  "Create a new HttpConnector instance."
  []
  (core/->HttpConnector))

(def connector-metadata
  "Registration metadata for the HTTP connector."
  {:connector/name         "HTTP REST Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/discovery :cap/incremental :cap/pagination :cap/rate-limiting}
   :connector/auth-methods #{:api-key :basic :none}
   :connector/retry-policy (conn/retry-policy :default)
   :connector/maintainer   "data-foundry"})

;; -- Cursor utilities (shared by HTTP-based source connectors) --
(def after-cursor?        cursors/after-cursor?)
(def last-record-cursor   cursors/last-record-cursor)
(def max-timestamp-cursor cursors/max-timestamp-cursor)
(def sort-by-timestamp    cursors/sort-by-timestamp)
(def parse-timestamp      cursors/parse-timestamp)

;; -- Request utilities (shared by HTTP-based source connectors) --
(def coerce-records    request/coerce-records)
(def do-request        request/do-request)
(def error-response    request/error-response)
(def next-url          request/next-url)
(def throw-on-failure! request/throw-on-failure!)

;; -- Rate-limit utilities --
(def acquire-permit!     rate-limit/acquire-permit!)
(def parse-rate-headers  rate-limit/parse-rate-headers)
(def time-based-acquire! rate-limit/time-based-acquire!)
(def update-rate-state!  rate-limit/update-rate-state!)
