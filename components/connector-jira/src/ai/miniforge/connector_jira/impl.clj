(ns ai.miniforge.connector-jira.impl
  "Implementation functions for the Jira Cloud REST API connector.
   Small composable functions organized in a stratified DAG."
  (:require [ai.miniforge.connector.interface :as connector]
            [ai.miniforge.connector-jira.messages :as msg]
            [ai.miniforge.connector-jira.resources :as resources]
            [ai.miniforge.connector-jira.schema :as schema]
            [ai.miniforge.connector-http.interface :as http])
  (:import [java.util Base64 UUID]))

;;------------------------------------------------------------------------------ Layer 0
;; Handle state

(def ^:private handles (connector/create-handle-registry))

(defn- store-handle! [handle state] (connector/store-handle! handles handle state))
(defn- remove-handle! [handle] (connector/remove-handle! handles handle))
(defn- touch-handle! [handle] (connector/touch-handle! handles handle))

(defn- require-handle!
  "Retrieve handle state or throw, delegating to the shared helper."
  [handle]
  (connector/require-handle! handles handle
                             {:message (msg/t :jira/handle-not-found {:handle handle})}))

;; Auth — Jira Cloud uses Basic auth (email:api-token)

(defn- build-auth-headers
  "Build authorization headers for Jira Cloud.
   Basic auth: Base64(email:api-token)."
  [config {:auth/keys [credential-id]}]
  (let [email (or (:jira/email config) (:auth/email config))
        token credential-id
        encoded (.encodeToString (Base64/getEncoder) (.getBytes (str email ":" token)))]
    {"Authorization" (str "Basic " encoded)
     "Accept"        "application/json"
     "Content-Type"  "application/json"}))

(defn- resolve-auth-headers
  "Build auth headers from config + auth, defaulting to empty map."
  [config auth]
  (if (:auth/credential-id auth)
    (build-auth-headers config auth)
    {}))

(defn- validate-auth!
  "Validate auth credential reference, throwing on failure.
   Delegates to the shared connector helper, then re-throws with the
   localized message that interpolates the actual validation errors.

   The shared helper accepts a `:connector` opt that gets folded into
   `:anomaly/data` for diagnostic logging, but here the throwing
   boundary should preserve the historical `{:errors ...}` payload
   shape, so we omit it."
  [auth]
  (when-let [a (connector/validate-auth auth)]
    (throw (ex-info (msg/t :jira/auth-invalid {:errors (:errors (:anomaly/data a))})
                    (:anomaly/data a)))))

;;------------------------------------------------------------------------------ Layer 1
;; HTTP — Jira uses offset pagination (startAt + maxResults), not Link headers.

(defn- error-response
  [status resp]
  (http/error-response status resp
    {:rate-limited   (msg/t :jira/rate-limited)
     :request-failed (fn [s e] (msg/t :jira/request-failed {:status s :error e}))}))

(defn- do-request
  [url headers query-params]
  (http/do-request url headers query-params error-response))

(defn- require-resource!
  "Look up resource def or throw."
  [schema-name]
  (or (resources/get-resource (keyword schema-name))
      (throw (ex-info (msg/t :jira/resource-unknown {:resource schema-name})
                      {:resource schema-name}))))

(defn- timestamp-value
  [record]
  (or (:updated record)
      (get-in record [:fields :updated])
      (get-in record [:fields :created])))

(defn- fetch-all-pages
  "Fetch all pages from a Jira endpoint using offset pagination.
   response-key is the JSON key containing the records array (e.g. :issues, :values)."
  [handle url headers query-params response-key]
  (loop [start-at   0
         accumulated []]
    (touch-handle! handle)
    (let [params (assoc query-params "startAt" start-at)
          resp   (http/throw-on-failure! (do-request url headers params))
          body   (:body resp)
          records (get body response-key [])
          all-records (into accumulated records)
          total  (get body :total (count all-records))]
      (if (or (empty? records) (>= (count all-records) total))
        all-records
        (recur (count all-records) all-records)))))

;;------------------------------------------------------------------------------ Layer 2
;; Lifecycle and source operations

(defn do-connect
  "Validate config at boundary, register handle."
  [config auth]
  (when-not (:jira/site config)
    (throw (ex-info (msg/t :jira/site-required) {:config config})))
  (schema/validate! schema/JiraConfig config)
  (validate-auth! auth)
  (let [handle (str (UUID/randomUUID))
        base-url (resources/build-base-url config)]
    (store-handle! handle {:config       (assoc config :jira/base-url base-url)
                            :auth-headers (resolve-auth-headers config auth)
                            :last-request-at nil})
    (connector/connect-result handle)))

(defn do-close [handle]
  (remove-handle! handle)
  (connector/close-result))

(defn do-discover [handle]
  (require-handle! handle)
  (connector/discover-result (resources/resource-schemas)))

(defn do-extract
  "Extract records from a Jira Cloud API resource.
   Validates records at the API boundary — malformed records from
   the Jira API are filtered out before entering the pipeline."
  [handle schema-name opts]
  (let [handle-state (require-handle! handle)
        resource-def (require-resource! schema-name)
        resource-key (keyword schema-name)
        {:keys [config auth-headers]} handle-state
        url          (resources/build-url (:jira/base-url config) resource-def config)
        params       (resources/build-query-params resource-def (:extract/cursor opts) opts config)
        response-key (get resource-def :response-key :values)
        raw-records  (fetch-all-pages handle url auth-headers params response-key)
        records      (schema/validate-records resource-key raw-records)
        cursor       (when (= :timestamp-watermark (:cursor-type resource-def))
                       (http/max-timestamp-cursor timestamp-value records))]
    (connector/extract-result records cursor false)))

(defn do-checkpoint [cursor-state]
  (connector/checkpoint-result cursor-state))
