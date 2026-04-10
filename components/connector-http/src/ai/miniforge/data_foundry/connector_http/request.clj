(ns ai.miniforge.data-foundry.connector-http.request
  "Shared HTTP GET request pattern for REST API connectors.
   Handles ETag conditional requests, response classification, and
   Link-header pagination. Connectors supply connector-specific error
   messages; everything else is shared.

   Layer 0: Predicates and classifiers
   Layer 1: Response builders
   Layer 2: Request execution and post-request helpers"
  (:require [ai.miniforge.data-foundry.connector-http.etag :as etag]
            [ai.miniforge.data-foundry.connector-http.pagination :as page]
            [ai.miniforge.schema.interface :as schema]
            [clojure.data.json :as json]
            [hato.client :as hc]))

;;------------------------------------------------------------------------------ Layer 0
;; Predicates and classifiers

(defn ok?
  "Predicate: is the HTTP status a success (2xx)?"
  [status]
  (<= 200 status 299))

(defn classify-error
  "Classify a non-success HTTP status into an error category keyword."
  [status]
  (cond
    (= 429 status)      :rate-limited
    (<= 500 status 599) :server-error
    :else               :client-error))

;;------------------------------------------------------------------------------ Layer 1
;; Response builders

(defn success-response
  "Build a schema/success from a 2xx response. Caches the ETag for the URL."
  [url resp]
  (etag/store-etag! url (etag/extract-etag (:headers resp)))
  (schema/success :body (json/read-str (:body resp) :key-fn keyword)
                  {:headers (:headers resp)}))

(defn not-modified-response
  "Build a schema/success for a 304 Not Modified response."
  [resp]
  (schema/success :body nil {:headers (:headers resp) :not-modified true}))

(defn error-response
  "Build a schema/failure from a non-success, non-304 response.
   msgs map: {:rate-limited string :request-failed (fn [status err-str] string)}"
  [status resp msgs]
  (let [request-failed (:request-failed msgs)]
    (case (classify-error status)
      :rate-limited (schema/failure :body (:rate-limited msgs)                      {:error-type :rate-limited})
      :server-error (schema/failure :body (request-failed status "server error")    {:error-type :transient})
      :client-error (schema/failure :body (request-failed status (:body resp))      {:error-type :permanent}))))

;;------------------------------------------------------------------------------ Layer 2
;; Request execution and post-request helpers

(defn do-request
  "Execute an HTTP GET. Returns schema/success or schema/failure.
   Supports ETag conditional requests via If-None-Match header.
   error-fn: (fn [status resp] schema/failure) — supplies connector-specific messages."
  [url headers query-params error-fn]
  (let [resp   (hc/get url {:headers          (etag/add-etag-header headers url)
                             :query-params     query-params
                             :as               :string
                             :throw-exceptions false})
        status (:status resp)]
    (cond
      (etag/not-modified? status) (not-modified-response resp)
      (ok? status)                (success-response url resp)
      :else                       (error-fn status resp))))

(defn throw-on-failure!
  "Throw an ex-info if result is a failure. Returns result unchanged on success."
  [result]
  (when-not (:success? result)
    (throw (ex-info (str (:error result)) {:error-type (:error-type result)})))
  result)

(defn next-url
  "Extract the 'next' page URL from a response's Link header, or nil."
  [resp]
  (some-> (:headers resp)
          (get "link")
          page/parse-link-header
          page/link-header-next-url))

(defn coerce-records
  "Coerce a response body to a vector of records."
  [body]
  (if (sequential? body) (vec body) [body]))
