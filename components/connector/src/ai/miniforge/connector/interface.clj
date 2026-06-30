(ns ai.miniforge.connector.interface
  (:require [ai.miniforge.connector.core :as core]
            [ai.miniforge.connector.cursor :as cursor]
            [ai.miniforge.connector.handles :as handles]
            [ai.miniforge.connector.protocol :as protocol]
            [ai.miniforge.connector.result :as result]
            [ai.miniforge.connector.retry :as retry]
            [ai.miniforge.connector.state :as state]
            [ai.miniforge.connector.validation :as validation]))

;; -- Handle registry (per-connector instance state) --
(def create-handle-registry
  "Create a new, empty handle registry. Returns an atom wrapping an empty map,
   used by a connector to hold its per-instance handle->state entries."
  handles/create)
(def get-handle
  "Look up handle state by handle id in `store`. Returns the stored state value,
   or nil when the handle is not present."
  handles/get-handle)
(def store-handle!
  "Associate handle id with `state` in `store`. Mutates the registry atom and
   returns the new registry map."
  handles/store-handle!)
(def remove-handle!
  "Remove `handle` and its state from `store`. Mutates the registry atom and
   returns the new registry map (without the handle)."
  handles/remove-handle!)
(def touch-handle!
  "Set `:last-request-at` for `handle` to the current epoch-millis. Mutates the
   registry atom and returns the new registry map."
  handles/touch-handle!)

;; -- Connector Protocol --
(def Connector
  "Base connector protocol. Implementations must provide connect and close."
  protocol/Connector)
(def SourceConnector
  "Source connector protocol for data extraction. Provides discover, extract,
   and checkpoint."
  protocol/SourceConnector)
(def SinkConnector
  "Sink connector protocol for data publication. Provides publish."
  protocol/SinkConnector)

;; -- Protocol Method Delegates --
(defn connect
  "Establish connection to external system."
  [connector config auth]
  (protocol/connect connector config auth))

(defn close
  "Terminate connection and release resources."
  [connector handle]
  (protocol/close connector handle))

(defn discover
  "Discover available schemas from source connector."
  [connector handle opts]
  (protocol/discover connector handle opts))

(defn extract
  "Extract records from source connector."
  [connector handle schema-name opts]
  (protocol/extract connector handle schema-name opts))

(defn checkpoint
  "Persist cursor state for source connector."
  [connector handle connector-id cursor-state]
  (protocol/checkpoint connector handle connector-id cursor-state))

(defn publish
  "Write records to sink connector."
  [connector handle schema-name records opts]
  (protocol/publish connector handle schema-name records opts))

;; -- Connector Types --
(def connector-types
  "Set of valid connector type keywords (N2 §1): #{:source :sink :bidirectional}."
  core/connector-types)
(def connector-capabilities
  "Set of valid connector capability keywords (N2 §1.4), e.g. :cap/discovery,
   :cap/incremental, :cap/batch, :cap/upsert, :cap/transactions,
   :cap/rate-limiting, :cap/pagination."
  core/connector-capabilities)

;; -- Retry Policy Presets --
(def retry-policies
  "Map of preset-key → retry-policy-map (loaded from EDN)."
  retry/retry-policies)

(defn retry-policy
  "Return the retry policy preset for `policy-key` (e.g. `:default`, `:none`).
   Throws when the key is unknown."
  [policy-key]
  (retry/retry-policy policy-key))

;; -- Registration --
(defn create-connector
  "Create a connector registration map.
   Required: :connector/name, :connector/type, :connector/version,
             :connector/capabilities, :connector/auth-methods,
             :connector/retry-policy, :connector/maintainer
   Returns {:success? true :connector ...} or {:success? false :errors [...]}"
  [opts]
  (core/create-connector opts))

(defn validate-connector
  "Validate a connector registration against N2 §7.1."
  [connector]
  (core/validate-connector connector))

;; -- Connector State --
(defn create-connector-state
  "Create initial ConnectorState for a connector instance."
  [connector-id]
  (state/create-connector-state connector-id))

(defn update-state
  "Update connector state. Returns new state."
  [state updates]
  (state/update-state state updates))

(defn record-batch
  "Record a batch of processed records in state."
  [state records-processed records-failed]
  (state/record-batch state records-processed records-failed))

;; -- Cursor --
(def cursor-types
  "Set of valid cursor type keywords (N2 §2.2.1):
   #{:timestamp-watermark :offset :sequence-id :version-id}."
  cursor/cursor-types)

(defn create-cursor
  "Create a cursor of the given type with initial value."
  [cursor-type initial-value]
  (cursor/create-cursor cursor-type initial-value))

(defn advance-cursor
  "Advance cursor to new position. Returns updated cursor."
  [cursor new-value]
  (cursor/advance-cursor cursor new-value))

(defn validate-cursor
  "Validate cursor structure."
  [cursor]
  (cursor/validate-cursor cursor))

;; -- Result Factories --
(def connect-result
  "Build the Connector/connect result map from `handle`. Returns
   {:connection/handle handle, :connector/status :connected,
    :connection/opened-at Instant}."
  result/connect-result)
(def close-result
  "Build the Connector/close result map. Takes no args. Returns
   {:connector/status :closed, :connection/closed-at Instant}."
  result/close-result)
(def discover-result
  "Build the SourceConnector/discover result map from `schemas` (a seq).
   Returns {:schemas schemas, :discover/total-count (count schemas)}."
  result/discover-result)
(def extract-result
  "Build the SourceConnector/extract result map from `records`, `cursor`, and
   `has-more`. Returns {:records records, :extract/cursor cursor,
   :extract/has-more has-more, :extract/row-count (count records),
   :extract/completed-at Instant}."
  result/extract-result)
(def checkpoint-result
  "Build the SourceConnector/checkpoint result map from `cursor-state`. Returns
   {:checkpoint/id UUID, :checkpoint/created Instant,
    :checkpoint/status :committed, :checkpoint/cursor cursor-state}."
  result/checkpoint-result)
(def publish-result
  "Build the SinkConnector/publish result map from `records-written` and
   `records-failed`. Returns {:publish/records-written records-written,
   :publish/records-failed records-failed}."
  result/publish-result)

;; -- Shared Validation Helpers --
;;
;; Anomaly-returning variants (preferred):
(def require-handle
  "Look up handle state in `store` by `handle`. Returns the stored state on
   success; returns a `:not-found` anomaly map when the handle is unknown.
   Optional 3rd arg `{:message string, :connector keyword}` customizes the
   anomaly message and adds the connector to `:anomaly/data`."
  validation/require-handle)
(def validate-auth
  "Validate credential reference `auth`. Returns nil when `auth` is nil or
   validation succeeds; returns an `:invalid-input` anomaly map when validation
   fails. Optional 2nd arg `{:message string, :connector keyword}` customizes
   the anomaly message and adds the connector to `:anomaly/data`."
  validation/validate-auth)
;; Auth throwing compatibility (deprecated; kept until auth callsites migrate):
(def validate-auth!
  "Validate credential reference `auth`. Returns nil on success (or when `auth`
   is nil); throws ex-info (:anomalies/incorrect) on validation failure.
   DEPRECATED: prefer validate-auth, which returns an anomaly instead."
  validation/validate-auth!)

;; Localized auth throwing compatibility (deprecated; kept until auth callsites
;; migrate):
(def validate-auth-or-throw!
  "Validate `auth`; on success return nil, on failure throw ex-info
   (:anomalies/incorrect) carrying a localized message and `{:errors [...]}`
   ex-data. Args: `auth` (credential ref or nil), `translator` (a messages/t
   function `(fn [key params])` bound to the connector's catalog), and
   `message-key` (catalog key whose template interpolates `{errors}`)."
  validation/validate-auth-or-throw!)
