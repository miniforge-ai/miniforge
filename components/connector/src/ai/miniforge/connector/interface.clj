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
(def create-handle-registry handles/create)
(def get-handle handles/get-handle)
(def store-handle! handles/store-handle!)
(def remove-handle! handles/remove-handle!)
(def touch-handle! handles/touch-handle!)

;; -- Connector Protocol --
(def Connector protocol/Connector)
(def SourceConnector protocol/SourceConnector)
(def SinkConnector protocol/SinkConnector)

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
(def connector-types core/connector-types)
(def connector-capabilities core/connector-capabilities)

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
(def cursor-types cursor/cursor-types)

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
(def connect-result result/connect-result)
(def close-result result/close-result)
(def discover-result result/discover-result)
(def extract-result result/extract-result)
(def checkpoint-result result/checkpoint-result)
(def publish-result result/publish-result)

;; -- Shared Validation Helpers --
;;
;; Anomaly-returning variants (preferred):
(def require-handle  validation/require-handle)
(def validate-auth   validation/validate-auth)
;; Throwing variants (deprecated; kept for incremental per-connector migration):
(def require-handle! validation/require-handle!)
(def validate-auth!  validation/validate-auth!)

;; Throwing-with-localized-message helper (used at the connector boundary
;; until all callers are migrated to the anomaly-returning variant):
(def validate-auth-or-throw! validation/validate-auth-or-throw!)
