(ns ai.miniforge.data-foundry.connector-http.impl
  "Implementation functions for the HTTP connector.
   Pure logic separated from protocol wiring."
  (:require [ai.miniforge.data-foundry.connector.handles :as h]
            [ai.miniforge.data-foundry.connector.result :as result]
            [ai.miniforge.data-foundry.connector-http.pagination :as page]
            [ai.miniforge.data-foundry.connector-http.messages :as msg]
            [ai.miniforge.schema.interface :as schema]
            [hato.client :as hc]
            [clojure.data.json :as json])
  (:import [java.util UUID]))

;; -- Handle state --

(def ^:private handles (h/create))

(defn get-handle [handle] (h/get-handle handles handle))
(defn store-handle! [handle state] (h/store-handle! handles handle state))
(defn remove-handle! [handle] (h/remove-handle! handles handle))
(defn touch-handle! [handle] (h/touch-handle! handles handle))

;; -- Auth --

(defn build-auth-headers
  "Build auth headers from auth config."
  [{:auth/keys [method credential-id]}]
  (case method
    :api-key {"Authorization" (str "Bearer " credential-id)}
    :basic   {"Authorization" (str "Basic " credential-id)}
    {}))

;; -- Rate limiting --

(defn enforce-rate-limit!
  "Sleep if needed to enforce requests-per-second."
  [handle-state]
  (when-let [rps (get-in handle-state [:config :http/rate-limit :requests-per-second])]
    (when-let [last-req (:last-request-at handle-state)]
      (let [min-interval-ms (/ 1000.0 rps)
            elapsed (- (System/currentTimeMillis) last-req)]
        (when (< elapsed min-interval-ms)
          (Thread/sleep (long (- min-interval-ms elapsed))))))))

;; -- HTTP --

(defn do-request
  "Execute an HTTP GET request. Returns schema/success or schema/failure."
  [url headers query-params]
  (let [resp (hc/get url {:headers          headers
                          :query-params     query-params
                          :as               :string
                          :throw-exceptions false})
        status (:status resp)]
    (cond
      (<= 200 status 299)
      (schema/success :body (json/read-str (:body resp) :key-fn keyword))

      (= 429 status)
      (schema/failure :body (msg/t :http/rate-limited)
                      {:error-type :rate-limited})

      (<= 500 status 599)
      (schema/failure :body (msg/t :http/request-failed {:status status :error "server error"})
                      {:error-type :transient})

      :else
      (schema/failure :body (msg/t :http/request-failed {:status status :error (:body resp)})
                      {:error-type :permanent}))))

(defn extract-records
  "Extract records from response body using configured response-path."
  [body response-path]
  (let [data (if response-path (get-in body response-path) body)]
    (cond
      (sequential? data) (vec data)
      (some? data)       [data]
      :else              [])))

;; -- Pagination helpers --

(defn build-page-params
  "Build query-params map for the current page."
  [pagination offset cursor-value batch-size]
  (case (:type pagination)
    :offset (page/offset-params pagination offset batch-size)
    :cursor (page/cursor-params pagination cursor-value batch-size)
    {}))

(defn page-has-more?
  "Determine if more pages exist after this batch."
  [pagination body offset record-count]
  (case (:type pagination)
    :offset (page/has-more-offset? body pagination offset record-count)
    :cursor (some? (page/extract-cursor-value body pagination))
    false))

(defn next-cursor-value
  "Compute the cursor value for the next page."
  [pagination body offset record-count]
  (case (:type pagination)
    :offset (page/next-offset offset record-count)
    :cursor (page/extract-cursor-value body pagination)
    nil))

;; -- Lifecycle --

(defn do-connect
  "Validate config, register handle. Returns connect-result."
  [config auth]
  (let [base-url (:http/base-url config)
        endpoint (:http/endpoint config)]
    (cond
      (nil? base-url) (throw (ex-info (msg/t :http/base-url-required) {:config config}))
      (nil? endpoint) (throw (ex-info (msg/t :http/endpoint-required) {:config config}))

      :else
      (let [handle       (str (UUID/randomUUID))
            auth-headers (if (and auth (:auth/method auth))
                           (build-auth-headers auth)
                           {})]
        (store-handle! handle {:config       config
                               :auth-headers auth-headers
                               :last-request-at nil})
        (result/connect-result handle)))))

(defn do-close [handle]
  (remove-handle! handle)
  (result/close-result))

;; -- Source --

(defn do-discover [handle]
  (if-let [{:keys [config]} (get-handle handle)]
    (result/discover-result [{:schema/name    (:http/endpoint config)
                              :schema/base-url (:http/base-url config)}])
    (throw (ex-info (msg/t :http/handle-not-found {:handle handle}) {:handle handle}))))

(defn- fetch-single
  "Fetch one page of records for a single query-params set.
   Returns the records vector."
  [url headers base-query-params response-path pagination opts]
  (let [batch-size   (or (:extract/batch-size opts) (get pagination :page-size 100))
        cursor-value (get-in opts [:extract/cursor :cursor/value])
        offset       (or cursor-value 0)
        query-params (merge base-query-params
                            (build-page-params pagination offset cursor-value batch-size))
        resp         (do-request url headers query-params)]
    (when-not (:success? resp)
      (throw (ex-info (str (:error resp)) {:error-type (:error-type resp)})))
    (let [body     (:body resp)
          records  (extract-records body response-path)
          has-more (page-has-more? pagination body offset (count records))
          next-val (next-cursor-value pagination body offset (count records))
          cursor   (when next-val
                     {:cursor/type  (or (:type pagination) :offset)
                      :cursor/value next-val})]
      {:records records :cursor cursor :has-more has-more})))

(defn do-extract
  "Fetch records from the HTTP API.
   If the handle's config contains :batch/param-sets, fetches all param sets
   in parallel via pmap, enriching each record with its param-set keys.
   Otherwise fetches a single page."
  [handle opts]
  (if-let [handle-state (get-handle handle)]
    (let [{:keys [config auth-headers]} handle-state
          url           (str (:http/base-url config) (:http/endpoint config))
          headers       (merge (:http/headers config {}) auth-headers)
          pagination    (:http/pagination config)
          base-qp       (:http/query-params config {})
          response-path (:http/response-path config)]

      (if-let [param-sets (:batch/param-sets config)]
        ;; Batch mode: pmap over param sets, enrich records with param keys
        (let [all-records (vec (apply concat
                                     (pmap (fn [params]
                                             (let [{:keys [records]} (fetch-single
                                                                      url headers
                                                                      (merge base-qp params)
                                                                      response-path pagination opts)]
                                               (mapv #(merge % params) records)))
                                           param-sets)))]
          (touch-handle! handle)
          (result/extract-result all-records nil false))

        ;; Single mode: one fetch with pagination
        (do
          (enforce-rate-limit! handle-state)
          (let [{:keys [records cursor has-more]}
                (fetch-single url headers base-qp response-path pagination opts)]
            (touch-handle! handle)
            (result/extract-result records cursor has-more)))))

    (throw (ex-info (msg/t :http/handle-not-found {:handle handle}) {:handle handle}))))

(defn do-checkpoint [cursor-state]
  (result/checkpoint-result cursor-state))
