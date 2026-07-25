;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0 (the "License");
;; you may not use this file except in compliance with the License.
;; You may obtain a copy of the License at
;;
;;     http://www.apache.org/licenses/LICENSE-2.0
;;
;; Unless required by applicable law or agreed to in writing, software
;; distributed under the License is distributed on an "AS IS" BASIS,
;; WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
;; See the License for the specific language governing permissions and
;; limitations under the License.
(ns ai.miniforge.connector-http.interface
  "Public API for the HTTP connector component."
  (:require [ai.miniforge.connector-http.core :as core]
            [ai.miniforge.connector-http.cursors :as cursors]
            [ai.miniforge.connector-http.rate-limit :as rate-limit]
            [ai.miniforge.connector-http.request :as request]
            [ai.miniforge.connector.interface :as conn]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} create-http-connector
  "Create a new HttpConnector instance."
  []
  (core/->HttpConnector))

(def ^{:stratum 0} connector-metadata
  "Registration metadata for the HTTP connector."
  {:connector/name         "HTTP REST Connector"
   :connector/type         :source
   :connector/version      "0.1.0"
   :connector/capabilities #{:cap/discovery :cap/incremental :cap/pagination :cap/rate-limiting}
   :connector/auth-methods #{:api-key :basic :none}
   :connector/retry-policy (conn/retry-policy :default)
   :connector/maintainer   "data-foundry"})

;; -- Cursor utilities (shared by HTTP-based source connectors) --
(def ^{:stratum 0} after-cursor?
  "Predicate (fn [timestamp-fn cursor record] boolean): true if the record's
   timestamp is strictly after the cursor's :cursor/value watermark. timestamp-fn
   extracts an ISO-8601 string (or nil) from a record. Returns true whenever the
   cursor carries no parseable :cursor/value timestamp — i.e. a nil cursor, a
   missing :cursor/value, or a blank/malformed one (no prior watermark)."
  cursors/after-cursor?)

(def ^{:stratum 0} last-record-cursor
  "(fn [resource-def records] cursor-map-or-nil): build a :timestamp-watermark
   cursor map {:cursor/type :timestamp-watermark :cursor/value iso-string} from the
   last record's :updated_at or :created_at. :cursor/value may be nil when the last
   record carries neither :updated_at nor :created_at. Returns nil when records is
   empty or the resource-def :cursor-type is not :timestamp-watermark."
  cursors/last-record-cursor)

(def ^{:stratum 0} max-timestamp-cursor
  "(fn [timestamp-fn records] cursor-map-or-nil): build a :timestamp-watermark
   cursor map {:cursor/type :timestamp-watermark :cursor/value iso-string} from the
   maximum timestamp across records. timestamp-fn extracts an ISO-8601 string (or
   nil) per record. Returns nil when records is empty or none carry a timestamp."
  cursors/max-timestamp-cursor)

(def ^{:stratum 0} sort-by-timestamp
  "(fn [timestamp-fn records] sorted-seq): sort records ascending by timestamp.
   timestamp-fn extracts an ISO-8601 string (or nil) per record; records with nil
   timestamps sort first."
  cursors/sort-by-timestamp)

(def ^{:stratum 0} parse-timestamp
  "(fn [value] Instant-or-nil): parse an ISO-8601 timestamp string to a
   java.time.Instant. Returns nil when value is nil, non-string, or blank; throws
   on a malformed non-blank string."
  cursors/parse-timestamp)

;; -- Request utilities (shared by HTTP-based source connectors) --
(def ^{:stratum 0} coerce-records
  "(fn [body] vector): coerce a parsed response body to a vector of records. A
   sequential body is returned as a vector; any other value is wrapped in a
   single-element vector."
  request/coerce-records)

(def ^{:stratum 0} do-request
  "(fn [url headers query-params error-fn] result): execute an HTTP GET, applying
   ETag conditional-request handling (If-None-Match). Returns a schema/success
   (2xx or 304 Not Modified) or schema/failure. error-fn (fn [status resp] failure)
   supplies connector-specific error messages for non-success, non-304 responses."
  request/do-request)

(def ^{:stratum 0} error-response
  "(fn [status resp msgs] failure): build a schema/failure from a non-success,
   non-304 response. msgs map: {:rate-limited string :server-error string
   :request-failed (fn [status err-str] string)} — callers supply their own
   localized labels. The :error-type in failure metadata is :rate-limited (429),
   :transient (5xx), or :permanent (other 4xx)."
  request/error-response)

(def ^{:stratum 0} next-url
  "(fn [resp] string-or-nil): extract the 'next' page URL from the response's Link
   header. Returns nil when no Link header or no next relation is present."
  request/next-url)

(def ^{:stratum 0} throw-on-failure!
  "(fn [result] result): return result unchanged on success; on failure throw an
   ExceptionInfo carrying :anomaly/category :anomalies/unavailable and the legacy
   :error-type key in ex-data."
  request/throw-on-failure!)

;; -- Rate-limit utilities --
(def ^{:stratum 0} acquire-permit!
  "(fn [handles-atom handle opts] nil): side-effecting. Inspect the handle's stored
   rate-limit state and, when remaining requests fall below the threshold, block the
   calling thread until the rate limit resets (capped at 60s) by deref-ing a promise
   that a task scheduled on a ScheduledExecutorService delivers. opts: {:threshold
   long} overrides the default (10). Returns nil."
  rate-limit/acquire-permit!)

(def ^{:stratum 0} parse-rate-headers
  "(fn [headers mapping] info-map-or-nil): extract rate-limit info from response
   headers. mapping: {:remaining header :reset header :limit header}. Returns
   {:remaining long :reset-epoch long :limit long-or-nil}, or nil when the
   remaining/reset headers are absent or unparseable."
  rate-limit/parse-rate-headers)

(def ^{:stratum 0} time-based-acquire!
  "(fn [rps last-request-ms] epoch-ms): side-effecting. Sleep if needed to honor an
   rps (requests-per-second) limit given the prior request's epoch-millis (0 or nil
   means no prior request). Returns the current epoch-millis for use as the next
   last-request-at."
  rate-limit/time-based-acquire!)

(def ^{:stratum 0} update-rate-state!
  "(fn [handles-atom handle rate-info] state-or-nil): side-effecting. Store
   rate-info under [handle :rate-limit] in the handles atom via swap!. No-op
   returning nil when rate-info is nil."
  rate-limit/update-rate-state!)
