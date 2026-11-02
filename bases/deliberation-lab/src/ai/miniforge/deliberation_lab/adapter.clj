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
  "Plain `try`: this base has no slingshot dependency and would add one
   only for its leaf catches (dewey 211, exemption 1). Unparseable text
   yields nil."
  [text]
  (try
    (json/parse-string text true)
    (catch Exception _ nil)))

(defn- ^{:stratum 0} metered-usage
  "Usage in the lab's terms: every input-side token, cached or not, counts
   as input."
  [usage]
  (let [count-of #(let [n (get usage %)] (if (nat-int? n) n 0))]
    {:input (+ (count-of :input-tokens) (count-of :cache-creation-input-tokens)
               (count-of :cache-read-input-tokens))
     :output (count-of :output-tokens)
     :provider-usd 0}))

(defn- ^{:stratum 0} metered?
  "True when `usage` is complete provider metering: an output count, and
   positive total input, cached or not. Every real request sends input, so
   zero total input marks the llm client's default for a response that
   reported none."
  [usage]
  (and (map? usage)
       (nat-int? (:output-tokens usage))
       (pos? (:input (metered-usage usage)))))


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

(defn ^{:stratum 1} call-result
  "The `call` result for one llm completion `result`: `{:payload :usage}`,
   or an anomaly when the call failed or reported no provider usage."
  [result]
  (cond
    (not (llm/success? result))
    (failure :anomalies.deliberation-lab/adapter-failure :anomaly/adapter-failure)

    (not (metered? (:usage result)))
    (failure :anomalies.deliberation-lab/missing-usage :anomaly/missing-usage)

    :else
    {:payload (decode (llm/get-content result)) :usage (metered-usage (:usage result))}))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 1} complete!
  "One streaming completion, or nil when the transport itself throws, as
   it does when the `claude` executable is missing. Plain `try`: see
   `parse-json`."
  [client request]
  (try
    (llm/complete-stream client request (constantly nil))
    (catch Exception _ nil)))

(defn ^{:stratum 2} claude-call
  "A `call` for `run-cell`: one isolated completion per request, through
   the streaming path, which parses the provider's usage, with the
   manifest's per-call dollar cap as the provider budget guard. A failed
   call is never retried."
  [{:keys [model adapter]}]
  (let [client (llm/create-client {:backend :claude :model model :isolated? true})
        prompt (system-prompt)]
    (fn [request]
      (call-result (complete! client
                              {:system prompt
                               :budget-usd (:call-cap-usd adapter)
                               :messages [{:role "user"
                                           :content (json/generate-string request)}]})))))
