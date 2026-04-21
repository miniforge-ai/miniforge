(ns ai.miniforge.connector-http.interface
  "Public API for the HTTP connector component.

   Only metadata and BB-safe cursor/rate-limit helpers are exposed eagerly.
   Request utilities and the HttpConnector record depend on hato, which
   isn't Babashka-compatible — they're resolved lazily so sibling
   connectors can keep `connector-http.interface` in their require list
   without breaking the GraalVM-compat gate."
  (:require [ai.miniforge.connector-http.cursors :as cursors]
            [ai.miniforge.connector-http.rate-limit :as rate-limit]
            [ai.miniforge.connector.interface :as conn]))

(defn create-http-connector
  "Create a new HttpConnector instance."
  []
  (@(requiring-resolve 'ai.miniforge.connector-http.core/->HttpConnector)))

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

;; -- Request utilities --
;; Lazy resolution: requiring the `request` namespace at interface load
;; time pulls in `hato.client` which is not Babashka-compatible. Source
;; connectors only call these from JVM-resolved code paths.
(defn coerce-records    [& args] (apply @(requiring-resolve 'ai.miniforge.connector-http.request/coerce-records) args))
(defn do-request        [& args] (apply @(requiring-resolve 'ai.miniforge.connector-http.request/do-request) args))
(defn error-response    [& args] (apply @(requiring-resolve 'ai.miniforge.connector-http.request/error-response) args))
(defn next-url          [& args] (apply @(requiring-resolve 'ai.miniforge.connector-http.request/next-url) args))
(defn throw-on-failure! [& args] (apply @(requiring-resolve 'ai.miniforge.connector-http.request/throw-on-failure!) args))

;; -- Rate-limit utilities --
(def acquire-permit!     rate-limit/acquire-permit!)
(def parse-rate-headers  rate-limit/parse-rate-headers)
(def time-based-acquire! rate-limit/time-based-acquire!)
(def update-rate-state!  rate-limit/update-rate-state!)
