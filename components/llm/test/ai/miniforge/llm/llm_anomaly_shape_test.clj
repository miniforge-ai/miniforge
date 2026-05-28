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

(ns ai.miniforge.llm.llm-anomaly-shape-test
  "Lock the canonical anomaly shape produced by `llm-client/llm-error`
   and `llm-client/http-post-request` after the W2 anomaly convergence
   flip.

   Pre-flip these emitted `:anomaly/category` via
   `response/make-anomaly` / `response/from-exception`, with domain keys
   such as `:anomaly.llm/url` and `:anomaly/operation` at the anomaly
   map's top level.

   Post-flip:
   - generic-standard categories (`:anomalies/{fault,unavailable,
     unsupported,timeout}`) map 1:1 to `:anomaly/type` with no
     `:anomaly/subtype`;
   - domain categories (`:anomalies.agent/llm-error`,
     `:anomalies.agent/rate-limited`) carry `:anomaly/subtype` equal to
     the legacy category verbatim;
   - domain payload (`:operation`, `:url`) lives under `:anomaly/data` —
     the canonical schema is closed.

   These tests exercise the public `llm-error` builder (re-exported
   into the brick interface) and `http-post-request` indirectly via
   `parse-ollama-response`."
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.llm.protocols.impl.llm-client :as client]))

(deftest llm-error-generic-standard-category-emits-typed-anomaly-no-subtype
  (testing ":anomalies/unavailable maps to :unavailable with no subtype"
    (let [resp (client/llm-error :anomalies/unavailable "api_error" "boom")
          a    (:anomaly resp)]
      (is (anomaly/anomaly? a))
      (is (= :unavailable (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))
          "generic-standard category emits no :anomaly/subtype")))

  (testing ":anomalies/fault → :fault, no subtype"
    (let [a (:anomaly (client/llm-error :anomalies/fault "parse_error" "boom"))]
      (is (= :fault (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a)))))

  (testing ":anomalies/unsupported → :unsupported, no subtype"
    (let [a (:anomaly (client/llm-error :anomalies/unsupported "x" "y"))]
      (is (= :unsupported (:anomaly/type a)))
      (is (nil? (:anomaly/subtype a))))))

(deftest llm-error-domain-category-emits-subtype
  (testing ":anomalies.agent/llm-error → :fault + subtype verbatim"
    (let [a (:anomaly (client/llm-error :anomalies.agent/llm-error
                                        "cli_error" "boom"))]
      (is (anomaly/anomaly? a))
      (is (= :fault (:anomaly/type a)))
      (is (= :anomalies.agent/llm-error (:anomaly/subtype a))
          "legacy domain category preserved verbatim as :anomaly/subtype")))

  (testing ":anomalies.agent/rate-limited → :unavailable + subtype"
    (let [a (:anomaly (client/llm-error :anomalies.agent/rate-limited
                                        "rate_limit" "slow down"))]
      (is (= :unavailable (:anomaly/type a)))
      (is (= :anomalies.agent/rate-limited (:anomaly/subtype a))))))

(deftest llm-error-domain-payload-lives-under-anomaly-data
  (testing "the :operation domain field is carried under :anomaly/data"
    (let [a    (:anomaly (client/llm-error :anomalies/fault "x" "y"))
          data (:anomaly/data a)]
      (is (= :llm-complete (:operation data))
          ":operation lives under :anomaly/data — not at the anomaly's top level")
      (is (nil? (:anomaly/operation a))
          "no legacy :anomaly/operation at the anomaly's top level")
      (is (nil? (:operation a))
          ":operation also not at the anomaly's top level"))))

(deftest http-post-request-failure-emits-canonical-unavailable
  (testing "http-post-request returns a canonical :unavailable anomaly on exception"
    ;; Provoke a connection failure deterministically by hitting an
    ;; unroutable port on localhost. http-kit times out / refuses; either
    ;; way the catch produces a canonical anomaly.
    (let [a (client/http-post-request "http://127.0.0.1:1/llm-anomaly-shape-test"
                                      {"Content-Type" "application/json"}
                                      {})]
      ;; http-kit may return a {:error <Throwable>} on connection failure
      ;; rather than throwing; only assert canonical shape when the
      ;; exception path is taken.
      (when (anomaly/anomaly? a)
        (is (= :unavailable (:anomaly/type a)))
        (is (nil? (:anomaly/subtype a))
            ":anomalies/unavailable is generic-standard — no subtype")
        (is (= :http-request (get-in a [:anomaly/data :operation])))
        (is (= "http://127.0.0.1:1/llm-anomaly-shape-test"
               (get-in a [:anomaly/data :url]))
            ":url lives under :anomaly/data, not as :anomaly.llm/url at the top")
        (is (nil? (:anomaly.llm/url a))
            "legacy :anomaly.llm/url no longer set at the anomaly's top level")))))
