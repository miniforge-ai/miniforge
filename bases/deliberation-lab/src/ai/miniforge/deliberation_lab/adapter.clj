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
(ns ai.miniforge.deliberation-lab.adapter
  "Model calls for the synthetic lab through the llm component's isolated
   Claude client: fresh, tool-free, no user settings, hooks or MCP servers.
   Synthetic inputs only; this is not a production Ariadne information-flow
   boundary. Token usage comes from the provider envelope, never from the
   model's own payload."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-lab.messages :as messages]
   [ai.miniforge.llm.interface :as llm]
   [cheshire.core :as json]
   [clojure.java.io :as io]
   [clojure.walk :as walk]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} system-prompt-resource
  "Classpath path of the fixed system prompt; its hash is pinned per run."
  "deliberation-lab/system-prompt.txt")

(def ^{:stratum 0} envelope-keys
  "The only fields a model response may carry."
  #{:operations :inspect :solution :rationale})

(def ^{:stratum 0} enum-fields
  "Payload fields whose JSON strings name keywords."
  [:op :type :kind :source-class :outcome])

(defn ^{:stratum 0} system-prompt []
  (slurp (io/resource system-prompt-resource)))

(defn- ^{:stratum 0} keywordize-enums [value]
  (if (map? value)
    (reduce (fn [m k] (if (string? (get m k)) (update m k keyword) m)) value enum-fields)
    value))

(defn- ^{:stratum 0} envelope? [payload]
  (and (map? payload)
       (every? envelope-keys (keys payload))
       (vector? (:operations payload))
       (vector? (:inspect payload))
       (every? string? (:inspect payload))
       (map? (:solution payload))
       (string? (:rationale payload))))

(defn- ^{:stratum 0} parse-json
  "Plain `try`: this base has no slingshot dependency and this is its one
   catch (dewey 211, exemption 1). Unparseable text yields nil."
  [text]
  (try
    (json/parse-string text true)
    (catch Exception _ nil)))

(defn- ^{:stratum 0} metered-usage
  "Usage in the lab's terms: every input-side token counts as input."
  [{:keys [input-tokens output-tokens cache-creation-input-tokens cache-read-input-tokens]
    :or {input-tokens 0 output-tokens 0 cache-creation-input-tokens 0 cache-read-input-tokens 0}}]
  (let [input (+ input-tokens cache-creation-input-tokens cache-read-input-tokens)]
    {:input input :output output-tokens :provider-usd 0}))

(defn- ^{:stratum 0} failure [subtype message-key]
  (anomaly/sub-anomaly :unavailable subtype (messages/system-t message-key)
                       {:usage-unknown? true}))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} decode
  "The response envelope in `text`, or an invalid payload the cell charges
   as a failed activation. Unknown fields — usage, actor, permissions — make
   the envelope invalid rather than being ignored."
  [text]
  (let [payload (some->> text parse-json (walk/postwalk keywordize-enums))]
    (if (envelope? payload)
      payload
      {:operations [] :inspect [] :solution {}
       :rationale (messages/system-t :lab/invalid-response) :invalid? true})))

(defn ^{:stratum 1} claude-call
  "A `call` for `run-cell`: one isolated completion per request. Returns
   `{:payload :usage}`, or an anomaly when the call fails or reports no
   usage; a failed call is never retried."
  [{:keys [model]}]
  (let [client (llm/create-client {:backend :claude :model model :isolated? true})
        prompt (system-prompt)]
    (fn [request]
      (let [result (llm/complete client {:system prompt
                                         :messages [{:role "user"
                                                     :content (json/generate-string request)}]})
            usage (:usage result)]
        (cond
          (not (llm/success? result))
          (failure :anomalies.deliberation-lab/adapter-failure :anomaly/adapter-failure)

          (not (map? usage))
          (failure :anomalies.deliberation-lab/missing-usage :anomaly/missing-usage)

          :else
          {:payload (decode (llm/get-content result)) :usage (metered-usage usage)})))))
