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

(ns ai.miniforge.agent.result-boundary-test
  (:require
   [ai.miniforge.agent.result-boundary :as sut]
   [clojure.test :refer [deftest is testing]]))

(deftest normalize-llm-result-prefers-worktree-metadata
  (testing "worktree metadata wins over MCP artifact, fallback artifact, and parsed stdout"
    (let [normalized (sut/normalize-llm-result
                      {:role :implement
                       :response {:success false
                                  :content "{:status :already-implemented :summary \"stdout\"}"
                                  :error {:message "backend failed"}}
                       :worktree-artifacts {:implement {:status :already-implemented
                                                        :summary "worktree"}}
                       :artifact {:status :already-implemented :summary "mcp"}
                       :fallback-artifact {:status :already-implemented :summary "fallback"}
                       :parse-response read-string})]
      (is (= :worktree-metadata (:artifact-source normalized)))
      (is (= "worktree" (:summary (sut/authoritative-payload normalized))))
      (is (true? (sut/usable-content? normalized))))))

(deftest normalize-llm-result-accepts-parseable-content-from-failure
  (testing "parseable stdout still yields a usable boundary when backend success is false"
    (let [normalized (sut/normalize-llm-result
                      {:response {:success false
                                  :content "{:status :already-implemented :summary \"done\"}"
                                  :error {:message "Adaptive timeout"}}
                       :parse-response read-string})]
      (is (false? (:response-success? normalized)))
      (is (= :already-implemented (:status (:parsed-content normalized))))
      (is (true? (sut/usable-content? normalized))))))

(deftest normalize-llm-result-merges-submit-metadata-with-file-fallback
  (testing "MCP submit metadata does not hide files collected from Write/Edit"
    (let [normalized (sut/normalize-llm-result
                      {:role :implement
                       :response {:success true :content ""}
                       :artifact {:code/summary "Added tool-call events"
                                  :code/tests-needed? true}
                       :fallback-artifact {:code/files [{:path "src/core.clj"
                                                         :content "(ns core)"
                                                         :action :modify}]
                                           :code/summary "fallback summary"
                                           :code/language "clojure"}})
          payload (sut/authoritative-payload normalized)]
      (is (= :mcp-with-file-fallback (:artifact-source normalized)))
      (is (= "Added tool-call events" (:code/summary payload)))
      (is (true? (:code/tests-needed? payload)))
      (is (= [{:path "src/core.clj" :content "(ns core)" :action :modify}]
             (:code/files payload)))
      (is (= "clojure" (:code/language payload))))))

;------------------------------------------------------------------------------ LLM-timeout classification

(deftest llm-timeout-type-extracts-canonical-keyword
  (testing "reads [:llm-error :timeout :type] from the normalized boundary"
    (is (= :stream-idle
           (sut/llm-timeout-type
             {:llm-error {:type "adaptive_timeout"
                          :message "Adaptive timeout: No stream output for 360000ms (type: stream-idle, ...)"
                          :timeout {:type :stream-idle
                                    :elapsed-ms 360087
                                    :message "No stream output for 360000ms"
                                    :stats {:lines-read 0}}}})))

    (is (= :network-drop
           (sut/llm-timeout-type
             {:llm-error {:type "adaptive_timeout"
                          :timeout {:type :network-drop
                                    :backend-key :claude
                                    :elapsed-ms 30000}}})))

    (is (= :stagnation
           (sut/llm-timeout-type
             {:llm-error {:timeout {:type :stagnation :elapsed-ms 180000}}})))

    (is (= :hard-limit
           (sut/llm-timeout-type
             {:llm-error {:timeout {:type :hard-limit :elapsed-ms 600000}}}))))

  (testing "returns nil for non-error / no-timeout normalized maps"
    (is (nil? (sut/llm-timeout-type {:llm-error nil})))
    (is (nil? (sut/llm-timeout-type {:llm-error {:type "cli_error" :message "Stderr"}}))
        "an LLM error without a :timeout envelope has no timeout-type")
    (is (nil? (sut/llm-timeout-type {}))
        "boundary with no :llm-error at all returns nil"))

  (testing "missing :type inside :timeout returns nil — does not crash on partial maps"
    (is (nil? (sut/llm-timeout-type
                {:llm-error {:timeout {:elapsed-ms 1000}}})))))

(deftest stream-idle-error?-identifies-stream-idle-timeouts
  ;; The 2026-06-05 dogfood (`adhoc-944448986`) reviewer-LLM stream-idle'd
  ;; at 360s; the framework synthesized a false `:rejected` because the
  ;; reviewer's parsed-review-based `timeout-only-review?` couldn't see
  ;; the boundary-level error. This boundary-level predicate is the
  ;; missing piece — fires on the production shape.
  (testing "production stream-idle shape"
    (is (true? (sut/stream-idle-error?
                 {:llm-error {:type "adaptive_timeout"
                              :message "Adaptive timeout: No stream output for 360000ms (type: stream-idle, elapsed: 360087ms)"
                              :timeout {:type :stream-idle
                                        :elapsed-ms 360087}}}))))

  (testing "other timeout types do NOT match — symmetry with network-drop / stagnation / hard-limit"
    (is (false? (sut/stream-idle-error?
                  {:llm-error {:timeout {:type :network-drop :elapsed-ms 30000}}})))
    (is (false? (sut/stream-idle-error?
                  {:llm-error {:timeout {:type :stagnation :elapsed-ms 180000}}})))
    (is (false? (sut/stream-idle-error?
                  {:llm-error {:timeout {:type :hard-limit :elapsed-ms 600000}}}))))

  (testing "non-timeout errors and missing-error normalized maps do NOT match"
    (is (false? (sut/stream-idle-error? {:llm-error nil})))
    (is (false? (sut/stream-idle-error? {:llm-error {:type "cli_error"}})))
    (is (false? (sut/stream-idle-error? {})))))

(deftest network-drop-error?-identifies-network-drop-timeouts
  ;; Symmetry-only coverage — the network-drop verdict is produced by
  ;; PR-B of the network-resilience stack (#1053); this boundary helper
  ;; exists so future cross-phase consumers can branch on the canonical
  ;; timeout taxonomy without each phase rolling its own get-in.
  (testing "production network-drop shape"
    (is (true? (sut/network-drop-error?
                 {:llm-error {:type "adaptive_timeout"
                              :timeout {:type :network-drop
                                        :backend-key :claude
                                        :elapsed-ms 30000}}}))))

  (testing "other timeout types do NOT match"
    (is (false? (sut/network-drop-error?
                  {:llm-error {:timeout {:type :stream-idle :elapsed-ms 360000}}})))
    (is (false? (sut/network-drop-error?
                  {:llm-error {:timeout {:type :stagnation :elapsed-ms 180000}}})))))

(deftest backend-timeout-error?-covers-all-adaptive-no-verdict-timeouts
  ;; A 2026-06-14 dogfood (rn-03) reviewer STAGNATION-timed-out; the
  ;; stream-idle-only check missed it, so it fell through to a synthesized
  ;; :rejected with the timeout text as a blocking issue. This predicate covers
  ;; all three no-verdict adaptive timeouts so any of them routes to the
  ;; backend-timeout (infra) path.
  (testing "stream-idle / stagnation / hard-limit all match"
    (is (true? (sut/backend-timeout-error? {:llm-error {:timeout {:type :stream-idle :elapsed-ms 360000}}})))
    (is (true? (sut/backend-timeout-error? {:llm-error {:timeout {:type :stagnation :elapsed-ms 318398}}})))
    (is (true? (sut/backend-timeout-error? {:llm-error {:timeout {:type :hard-limit :elapsed-ms 600000}}}))))
  (testing "network-drop is excluded (dedicated retry/resume handling)"
    (is (false? (sut/backend-timeout-error? {:llm-error {:timeout {:type :network-drop :elapsed-ms 30000}}}))))
  (testing "non-timeout / missing-error maps do NOT match"
    (is (false? (sut/backend-timeout-error? {:llm-error {:type "cli_error"}})))
    (is (false? (sut/backend-timeout-error? {})))))

(deftest error-response-preserves-backend-error-shape
  (testing "error-response carries raw backend data and response metadata through"
    (let [normalized {:llm-error {:message "Adaptive timeout"
                                  :type "adaptive_timeout"
                                  :raw-stdout "stream"}
                      :stop-reason :timeout
                      :num-turns 3
                      :tokens 17}
          result (sut/error-response normalized "LLM call failed")]
      (is (= :error (:status result)))
      (is (= "Adaptive timeout" (get-in result [:error :message])))
      (is (= "adaptive_timeout" (get-in result [:error :data :type])))
      (is (= "stream" (get-in result [:error :data :raw-stdout])))
      (is (= :timeout (get-in result [:error :data :stop-reason])))
      (is (= 3 (get-in result [:error :data :num-turns])))
      (is (= 17 (get-in result [:metrics :tokens]))))))
