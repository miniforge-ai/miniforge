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
(ns ai.miniforge.pipeline-runner.connector-stage
  "Ingest and publish stage executors: open the stage's connector, extract or
   publish through it, and close it. Also builds the cursor entries the
   scheduler records for ingest stages."
  (:require [ai.miniforge.connector.interface :as conn]
            [ai.miniforge.pipeline-runner.messages :as msg]
            [ai.miniforge.pipeline-runner.stage-result
             :refer [stage-result timestamps exception-context anomaly-context]]
            [ai.miniforge.response.interface :as response])
  (:import [java.time Instant]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} default-config
  "Default execution configuration. Override via stage :config map."
  {:connector/page-size  1000
   :publish/default-mode :append})

(defn- ^{:stratum 0} close-handle!
  "Best-effort connector cleanup; close failures must not mask stage outcomes."
  [connector handle]
  (when handle
    (try
      (conn/close connector handle)
      (catch Exception _ nil))))

(def ^{:stratum 0} ^:private auth-keys
  "Keys that belong to auth config, extracted from stage config."
  #{:auth/method :auth/credential-id :auth/credential-scope
    :auth/client-id :auth/token-endpoint :auth/certificate-path :auth/vault-path})

(defn- ^{:stratum 0} resolve-schema-name
  "Resolve the schema-name for a connector extract call.
   Connectors use a resource key (e.g. \"issues\", \"pulls\") not the stage name.
   Check for common resource config keys, falling back to the stage name."
  [config stage-name]
  (or (:github/resource config)
      (:gitlab/resource config)
      (:resource config)
      stage-name))

(defn- ^{:stratum 0} extract-cursor-map
  [context]
  (or (:pipeline-run/connector-cursors context)
      (:connector-cursors context)
      {}))

(defn- ^{:stratum 0} cursor-entry->cursor
  [entry]
  (cond
    (nil? entry) nil
    (:cursor entry) (:cursor entry)
    (:cursor/type entry) entry
    :else nil))

(defn ^{:stratum 0} cursor-entry
  [{:stage/keys [id name connector-ref]} schema-name cursor]
  {:stage/id            id
   :stage/name          name
   :stage/connector-ref connector-ref
   :stage/schema-name   schema-name
   :cursor              cursor
   :cursor/updated-at   (Instant/now)})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} extract-auth
  "Extract auth keys from a config map into a separate auth map."
  [config]
  (not-empty (select-keys config auth-keys)))

(defn- ^{:stratum 1} prior-cursor
  [{:stage/keys [id connector-ref config name]} context]
  (let [schema-name (resolve-schema-name config name)
        cursor-map  (extract-cursor-map context)]
    (or (some-> (get cursor-map id) cursor-entry->cursor)
        (some-> (get cursor-map [connector-ref schema-name]) cursor-entry->cursor)
        (some-> (get cursor-map connector-ref) cursor-entry->cursor))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} execute-ingest-stage
  "Execute an ingest stage using the connector's extract method."
  [{:stage/keys [id name connector-ref config]} connectors context]
  (let [connector (get connectors connector-ref)]
    (if (nil? connector)
      (stage-result id name :failed
                    {:error-message (msg/t :run/connector-not-found {:ref connector-ref})})
      (let [handle (atom nil)]
        (try
          (let [auth           (or (extract-auth config) (get context :auth {}))
                schema         (resolve-schema-name config name)
                cursor         (prior-cursor {:stage/id id
                                              :stage/name name
                                              :stage/connector-ref connector-ref
                                              :stage/config config}
                                             context)
                connect-result (conn/connect connector (or config {}) auth)]
            (if (response/anomaly-map? connect-result)
              (stage-result id name :failed (anomaly-context connect-result))
              (do
                (reset! handle (:connection/handle connect-result))
                (let [result (conn/extract connector @handle schema
                                           (cond-> {:extract/batch-size (get config :connector/page-size
                                                                             (:connector/page-size default-config))}
                                             cursor (assoc :extract/cursor cursor)))]
                  (if (response/anomaly-map? result)
                    (stage-result id name :failed (anomaly-context result))
                    (stage-result id name :completed
                                  (merge (timestamps (get context :started-at))
                                         {:schema-name schema
                                          :records (:records result)
                                          :cursor (:extract/cursor result)})))))))
          (catch Exception e
            (stage-result id name :failed (exception-context e)))
          (finally
            (close-handle! connector @handle)))))))

(defn ^{:stratum 2} execute-publish-stage
  "Execute a publish stage using the connector's publish method."
  [{:stage/keys [id name connector-ref config]} connectors context input-records]
  (let [connector (get connectors connector-ref)]
    (if (nil? connector)
      (stage-result id name :failed
                    {:error-message (msg/t :run/connector-not-found {:ref connector-ref})})
      (let [handle (atom nil)]
        (try
          (let [auth           (or (extract-auth config) (:auth context {}))
                connect-result (conn/connect connector (or config {}) auth)]
            (if (response/anomaly-map? connect-result)
              (stage-result id name :failed (anomaly-context connect-result))
              (do
                (reset! handle (:connection/handle connect-result))
                (let [result (conn/publish connector @handle name input-records
                                           {:publish/mode     (get config :publish-mode
                                                                  (:publish/default-mode default-config))
                                            :publish/datasets (:publish/datasets context)})]
                  (if (response/anomaly-map? result)
                    (stage-result id name :failed (anomaly-context result))
                    (stage-result id name :completed {:completed-at (Instant/now)}))))))
          (catch Exception e
            (stage-result id name :failed (exception-context e)))
          (finally
            (close-handle! connector @handle)))))))

(comment
  (cursor-entry {:stage/id (random-uuid) :stage/name "Ingest" :stage/connector-ref (random-uuid)}
                "issues"
                {:cursor/type :offset :cursor/value 10}))
