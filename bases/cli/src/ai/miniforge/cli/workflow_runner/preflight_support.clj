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
(ns ai.miniforge.cli.workflow-runner.preflight-support
  "Backend-preflight plumbing: the workflow-runner config accessors the
   preflight reads (prompt, timeouts, claude args) and the
   response/payload shaping shared by the probe implementations
   (success/failure envelopes, stream decoding, canonical `{:ok true}`
   payload detection). Split out of `ai.miniforge.cli.workflow-runner`
   (rule 210: the parent namespace measured 10 real layers, max 3; each
   concern moves to its own layer-coherent file)."
  (:require
   [cheshire.core :as json]
   [clojure.string :as str]
   [ai.miniforge.cli.resource-config :as resource-config]
   [ai.miniforge.response.interface :as response]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private default-preflight-attempts
  "Health-probe attempts before the preflight fails closed, when the
   config is silent. Three: on 2026-09-03 four runs were refused on a
   single 30s timeout that landed within ~30s of a previous long run
   ending, while a hand probe answered inside a minute. One retry covers
   that window; the third attempt is margin against a slower recovery."
  3)

(def ^{:stratum 0} ^:private default-preflight-retry-pause-ms
  "Pause between failed probe attempts when the config is silent (2s):
   long enough for a CLI still releasing a prior session to finish,
   short enough that a hard failure still surfaces well inside two
   minutes across three 30s attempts."
  2000)

(def ^{:stratum 0} ^:private workflow-runner-config
  "Merged workflow-runner resource config. Only the :backend-preflight
   section is consumed today, which is why the delay lives here rather
   than in the parent namespace."
  (delay
    (resource-config/merged-resource-config "config/cli/workflow-runner.edn"
                                            :workflow-runner
                                            {})))

(defn ^{:stratum 0} success-response
  [output]
  (response/success output))

(defn ^{:stratum 0} failure-response
  [category error-type message data]
  (-> (response/failure message {:data (assoc data :type error-type)})
      (assoc :anomaly (response/make-anomaly category message data))))

(defn ^{:stratum 0} response-output
  [response]
  (let [output (get response :output)]
    (merge (select-keys response [:content :exit-code :version])
           (if (map? output) output {}))))

(defn ^{:stratum 0} response-succeeded?
  [response]
  (or (true? (:success response))
      (response/success? response)))

(defn ^{:stratum 0} with-backend-version
  [stamp version]
  (assoc stamp :cmd-version version))

(defn- ^{:stratum 0} parse-preflight-payload
  [content]
  (try
    (some-> content str/trim not-empty (json/parse-string true))
    (catch Exception _ nil)))

(defn- ^{:stratum 0} backend-stream-content
  [stream-parser output]
  (let [content (atom "")]
    (doseq [line (str/split-lines (or output ""))]
      (when-let [parsed (stream-parser line)]
        (when-let [delta (:delta parsed)]
          (swap! content str delta))))
    (some-> @content str/trim not-empty)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} backend-preflight-config []
  (:backend-preflight @workflow-runner-config))

(defn ^{:stratum 1} response-summary
  [response]
  (merge
   (select-keys response [:success :error :anomaly :exit-code])
   (select-keys (response-output response)
                [:content :exit-code :version])))

(defn ^{:stratum 1} normalized-preflight-content
  [content]
  (loop [candidate (some-> content str/trim not-empty)]
    (let [parsed (parse-preflight-payload candidate)
          nested (or (some-> parsed :result str/trim not-empty)
                     (some-> parsed :content str/trim not-empty))]
      (cond
        (and (:result parsed)
             (not (and (= "result" (:type parsed))
                       (= "success" (:subtype parsed))
                       (not (:is_error parsed)))))
        nil

        (and nested (not= nested candidate))
        (recur nested)

        :else
        candidate))))

(defn ^{:stratum 1} decoded-preflight-content
  [backend-config output]
  (if-let [stream-parser (:stream-parser backend-config)]
    (backend-stream-content stream-parser output)
    (some-> output str/trim not-empty)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} backend-preflight-prompt []
  (:prompt (backend-preflight-config)))

(defn ^{:stratum 2} backend-preflight-timeout-ms []
  (:timeout-ms (backend-preflight-config)))

(defn ^{:stratum 2} backend-version-timeout-ms []
  (:version-timeout-ms (backend-preflight-config)))

(defn ^{:stratum 2} backend-preflight-attempts
  "Configured probe attempts, clamped to at least one: the probe always
   runs once, so a zero or negative setting would make the logged
   `:attempts` disagree with what happened."
  []
  (max 1 (get (backend-preflight-config) :attempts default-preflight-attempts)))

(defn ^{:stratum 2} backend-preflight-retry-pause-ms []
  (get (backend-preflight-config) :retry-pause-ms default-preflight-retry-pause-ms))

(defn ^{:stratum 2} claude-preflight-args []
  (:claude-args (backend-preflight-config)))

(defn ^{:stratum 2} preflight-success?
  [content]
  (= {:ok true} (parse-preflight-payload (normalized-preflight-content content))))
