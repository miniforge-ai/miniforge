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
(ns ai.miniforge.llm.unknown-cost-test
  "A streamed response reports :cost-usd only when the cost is known: the
   CLI reported it, or the model has a price to estimate from. An unpriced
   model's cost is absent, not 0.0, so nothing downstream shows it as a
   measured $0.0000 (#2378)."
  (:require
   [clojure.test :refer [deftest testing is]]
   [cheshire.core :as json]
   [ai.miniforge.llm.interface :as llm]
   [ai.miniforge.llm.protocols.impl.llm-client :as impl]
   [ai.miniforge.llm.protocols.records.llm-client :as records]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} codex-turn
  [{:type "item.completed"
    :item {:id "item_0" :type "agent_message" :text "Done."}}
   {:type "turn.completed"
    :usage {:input_tokens 24763 :cached_input_tokens 24448 :output_tokens 122
            :reasoning_output_tokens 64}}])

(defn ^{:stratum 0} codex-response
  "Stream `events` from a Codex client (with `model`, when given) that exits
   with `exit`. `request` is merged into the streamed request."
  ([model events exit]
   (codex-response model {} events exit))
  ([model request events exit]
   (let [client (records/create-client
                 (cond-> {:backend :codex
                          :stream-exec-fn (fn [_cmd on-line _opts]
                                            (doseq [event events]
                                              (on-line (json/generate-string event)))
                                            {:out "" :err "" :exit exit})}
                   model (assoc :model model)))]
     (llm/complete-stream client (merge {:prompt "go"} request) (fn [_] nil)))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} unpriced-model-reports-no-cost-test
  (testing "a Codex run on the CLI's default model: tokens yes, cost unknown"
    (let [resp (codex-response nil codex-turn 0)]
      (is (:success resp))
      (is (= (+ 24763 122) (:tokens resp)))
      (is (not (contains? resp :cost-usd)))))
  (testing "a failed run on an unpriced model keeps its tokens, not a 0.0 cost"
    (let [resp (codex-response nil (conj codex-turn {:type "turn.failed"
                                                     :error {:message "boom"}})
                               1)]
      (is (not (:success resp)))
      (is (pos? (:tokens resp)))
      (is (not (contains? resp :cost-usd))))))

(deftest ^{:stratum 1} priced-model-cost-is-estimated-test
  (testing "a model in the cost table gets an estimate from its usage"
    (let [resp (codex-response "gpt-5.4" codex-turn 0)]
      (is (:success resp))
      (is (pos? (:cost-usd resp)))
      (is (= (llm/estimate-cost (:usage resp) "gpt-5.4") (:cost-usd resp)))))
  (testing "a Claude CLI run whose usage reports output tokens but no input
            tokens gets no estimate, not a partial figure"
    (let [client (records/create-client
                  {:backend :claude
                   :model "claude-sonnet-4-6"
                   :stream-exec-fn (fn [_cmd on-line _opts]
                                     (on-line (json/generate-string
                                               {:type "assistant"
                                                :message {:content [{:type "text" :text "partial"}]
                                                          :usage {:output_tokens 250}}}))
                                     (impl/timeout-result [] {:type :hard-limit
                                                              :message "Hard timeout"
                                                              :elapsed-ms 600042
                                                              :max-ms 600000
                                                              :stats {}}))})
          resp (llm/complete-stream client {:prompt "go"} (fn [_] nil))]
      (is (not (:success resp)))
      (is (pos? (:tokens resp)))
      (is (not (contains? resp :cost-usd)))))
  (testing "a Claude CLI run that read or wrote the prompt cache gets no
            estimate: the cache tokens are counted beside input and the
            estimate would leave them out"
    (let [client (records/create-client
                  {:backend :claude
                   :model "claude-sonnet-4-6"
                   :stream-exec-fn (fn [_cmd on-line _opts]
                                     (on-line (json/generate-string
                                               {:type "assistant"
                                                :message {:content [{:type "text" :text "partial"}]
                                                          :usage {:input_tokens 1000
                                                                  :output_tokens 250
                                                                  :cache_read_input_tokens 50000}}}))
                                     (impl/timeout-result [] {:type :hard-limit
                                                              :message "Hard timeout"
                                                              :elapsed-ms 600042
                                                              :max-ms 600000
                                                              :stats {}}))})
          resp (llm/complete-stream client {:prompt "go"} (fn [_] nil))]
      (is (not (:success resp)))
      (is (pos? (:tokens resp)))
      (is (not (contains? resp :cost-usd)))))
  (testing "the request's model is priced when the client names none"
    (let [resp (codex-response nil {:model "gpt-5.4"} codex-turn 0)]
      (is (pos? (:cost-usd resp)))
      (is (= (llm/estimate-cost (:usage resp) "gpt-5.4") (:cost-usd resp))))))
