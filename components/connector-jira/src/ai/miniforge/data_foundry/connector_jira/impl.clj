(ns ai.miniforge.data-foundry.connector-jira.impl
  "Implementation functions for the Jira Cloud REST API connector.
   Small composable functions organized in a stratified DAG."
  (:require [ai.miniforge.data-foundry.connector.handles :as h]
            [ai.miniforge.data-foundry.connector.result :as result]
            [ai.miniforge.data-foundry.connector-auth.core :as auth]
            [ai.miniforge.data-foundry.connector-jira.messages :as msg]
            [ai.miniforge.data-foundry.connector-jira.resources :as resources]
            [ai.miniforge.data-foundry.connector-jira.schema :as schema]
            [ai.miniforge.data-foundry.connector-http.cursors :as cursors]
            [ai.miniforge.data-foundry.connector-http.request :as request])
  (:import [java.util Base64 UUID]))

;;------------------------------------------------------------------------------ Layer 0
;; Handle state

(def ^:private handles (h/create))

(defn- get-handle    [handle] (h/get-handle handles handle))
(defn- store-handle! [handle state] (h/store-handle! handles handle state))
(defn- remove-handle! [handle] (h/remove-handle! handles handle))
(defn- touch-handle! [handle] (h/touch-handle! handles handle))

(defn- require-handle!
  "Retrieve handle state or throw."
  [handle]
  (or (get-handle handle)
      (throw (ex-info (msg/t :jira/handle-not-found {:handle handle})
                      {:handle handle}))))

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
  "Validate auth credential reference, throwing on failure."
  [auth]
  (when auth
    (let [result (auth/validate-credential-ref auth)]
      (when-not (:success? result)
        (throw (ex-info (msg/t :jira/auth-invalid {:errors (:errors result)})
                        {:errors (:errors result)}))))))

;;------------------------------------------------------------------------------ Layer 1
;; HTTP — Jira uses offset pagination (startAt + maxResults), not Link headers.

(defn- error-response
  [status resp]
  (request/error-response status resp
    {:rate-limited   (msg/t :jira/rate-limited)
     :request-failed (fn [s e] (msg/t :jira/request-failed {:status s :error e}))}))

(defn- do-request
  [url headers query-params]
  (request/do-request url headers query-params error-response))

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
          resp   (request/throw-on-failure! (do-request url headers params))
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
    (result/connect-result handle)))

(defn do-close [handle]
  (remove-handle! handle)
  (result/close-result))

(defn do-discover [handle]
  (require-handle! handle)
  (result/discover-result (resources/resource-schemas)))

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
                       (cursors/max-timestamp-cursor timestamp-value records))]
    (result/extract-result records cursor false)))

(defn do-checkpoint [cursor-state]
  (result/checkpoint-result cursor-state))
