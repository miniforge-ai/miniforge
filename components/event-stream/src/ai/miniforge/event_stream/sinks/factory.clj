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
(ns ai.miniforge.event-stream.sinks.factory
  "Configurable event sinks for different deployment scenarios.

   Supported sinks:
   - :file   - Write to ~/.miniforge/events/<workflow-id>/<filename>
               (local dev). The filename grammar is conditional on the
               envelope's :event/id:
                 * Snowflake-encoded UUID (low 64 bits zero, BD-2b) →
                   {event-id-hex16}__{workflow-seq-dec12}.transit.json,
                   sortable lex by creation order across workflows;
                 * any other UUID (no snowflake generator wired into the
                   stream, or legacy files on disk) →
                   {timestamp}-{uuid}.json, sortable by the ts prefix.
               Both shapes coexist; the reader accepts either.
   - :stdout - Print to stdout (container/Docker/K8s)
   - :stderr - Print to stderr (error-only events)
   - :fleet  - Send to fleet command (org-level ops)
   - :multi  - Combine multiple sinks"
  (:require
   [ai.miniforge.event-stream.sinks.console :as console]
   [ai.miniforge.event-stream.sinks.file :as file]
   [ai.miniforge.event-stream.sinks.fleet :as fleet]
   [ai.miniforge.response.interface :as response]
   [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} multi-sink
  "Create a multi-sink that writes to multiple sinks simultaneously.

   Arguments:
     sinks - Vector of sink functions

   Returns: Combined sink function (fn [event] -> nil)"
  [sinks]
  (fn [event]
    (doseq [sink sinks]
      (try
        (sink event)
        (catch Exception _e
          ;; Continue with other sinks even if one fails
          nil)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} create-sink
  "Create a sink from configuration.

   Arguments:
     sink-config - Keyword or map:
       :file - File sink
       :stdout - Stdout sink
       :stderr - Stderr sink
       {:type :fleet :url \"...\" :api-key \"...\"} - Fleet sink
       {:type :multi :sinks [...]} - Multi-sink

   Returns: Sink function (fn [event] -> nil)"
  [sink-config]
  (cond
    ;; Keyword shortcuts
    (= sink-config :file) (file/file-sink)
    (= sink-config :stdout) (console/stdout-sink)
    (= sink-config :stderr) (console/stderr-sink {:filter (fn [e]
                                                     (contains? #{:error :workflow/failed}
                                                               (:event/type e)))})

    ;; Map configurations
    (map? sink-config)
    (case (:type sink-config)
      :file (file/file-sink (dissoc sink-config :type))
      :stdout (console/stdout-sink (dissoc sink-config :type))
      :stderr (console/stderr-sink (dissoc sink-config :type))
      :fleet (fleet/fleet-sink (dissoc sink-config :type))
      :multi (multi-sink (map create-sink (:sinks sink-config)))
      (response/throw-anomaly! :anomalies/unsupported
                               "Unknown sink type"
                               {:type (:type sink-config)}))

    ;; Vector - treat as multi-sink
    (vector? sink-config)
    (multi-sink (map create-sink sink-config))

    :else
    (response/throw-anomaly! :anomalies/incorrect
                             "Invalid sink configuration"
                             {:config sink-config
                              :config/error :invalid-config})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} create-sinks-from-config
  "Create sinks from user configuration.

   Arguments:
     config - Config map with :observability {:event-sinks [...]}

   Returns: Vector of sink functions

   Example config:
     {:observability {:event-sinks [:file :stdout]
                      :fleet {:url \"https://fleet.miniforge.ai\"
                              :api-key \"key123\"}}}

   Environment variables:
     MINIFORGE_EVENT_SINKS=file,stdout
     MINIFORGE_FLEET_URL=https://fleet.miniforge.ai
     MINIFORGE_FLEET_API_KEY=key123"
  [config]
  (let [;; Get sinks from config or env var
        sinks-config (or (get-in config [:observability :event-sinks])
                        (when-let [env (System/getenv "MINIFORGE_EVENT_SINKS")]
                          (mapv keyword (str/split env #",")))
                        [:file]) ;; Default to file sink

        ;; Get fleet config from config or env vars
        fleet-config (or (get-in config [:observability :fleet])
                        (when (System/getenv "MINIFORGE_FLEET_URL")
                          {:url (System/getenv "MINIFORGE_FLEET_URL")
                           :api-key (System/getenv "MINIFORGE_FLEET_API_KEY")}))

        ;; Replace :fleet keyword with full fleet config
        sinks-config (mapv (fn [sink]
                            (if (= sink :fleet)
                              (assoc fleet-config :type :fleet)
                              sink))
                          sinks-config)]
    (mapv create-sink sinks-config)))
