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

(ns ai.miniforge.response.translate-test
  "anomaly→{user-message, http-response, log-data, event-data,
   outcome-evidence} translators all read the anomaly's classification
   keyword via a shape-bridging accessor: prefer :anomaly/subtype,
   fall back to :anomaly/category, then :anomaly/type. This covers
   the response→anomaly convergence transition where producers flip
   gradually but every existing classification outcome is preserved."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.response.interface :as response]))

(defn- legacy-anomaly
  "Un-migrated producer shape — :anomaly/category only."
  [category]
  {:anomaly/category category
   :anomaly/message "msg"})

(defn- canonical-anomaly
  "Post-flip producer shape — :anomaly/type + :anomaly/subtype (the legacy
   category keyword carried verbatim)."
  [type subtype]
  {:anomaly/type type
   :anomaly/subtype subtype
   :anomaly/message "msg"})

;; Domain-payload shape builders. `legacy-payload` puts each :anomaly.<dom>/*
;; key at the top level (pre-flip producer shape); `canonical-payload` nests
;; the equivalent :<dom>/* key under :anomaly/data (post-flip shape). Both
;; share `category` / `type` / `subtype` so the resulting anomalies dispatch
;; to the same classification.

(defn- legacy-payload
  "Pre-flip producer shape — domain payload keys live at the top level."
  [category message payload]
  (merge {:anomaly/category category
          :anomaly/message message}
         payload))

(defn- canonical-payload
  "Post-flip producer shape — domain payload keys are nested under
   :anomaly/data with the canonical `:<domain>/*` form."
  [type subtype message data]
  {:anomaly/type type
   :anomaly/subtype subtype
   :anomaly/message message
   :anomaly/data data})

(deftest user-message-honors-subtype-and-category-and-type
  (testing "the same classification produces the same user message whether
            the anomaly carries the legacy :anomaly/category, the canonical
            :anomaly/subtype, or only a generic :anomaly/type"
    (let [legacy-msg (response/anomaly->user-message
                      (legacy-anomaly :anomalies/timeout))
          canonical-msg (response/anomaly->user-message
                         (canonical-anomaly :timeout :anomalies/timeout))]
      (is (= legacy-msg canonical-msg)
          "subtype-driven and category-driven user messages are identical"))
    (is (string? (response/anomaly->user-message {:anomaly/type :fault}))
        "type-only generic anomalies still get a (fallback) string message")))

(deftest user-message-never-throws-on-missing-classification
  (testing "an anomaly with no :anomaly/subtype, :anomaly/category, or
            :anomaly/type does not NPE on (name nil); it falls back to a
            localized 'unknown error' string. Preserves the translator's
            'never exposes internal details, never throws' contract."
    (is (string? (response/anomaly->user-message {})))
    (is (string? (response/anomaly->user-message
                  {:anomaly/message "internal"})))))

(deftest http-status-honors-subtype-over-category
  (testing "subtype wins when both are set — half-migrated maps cannot
            revert the status via a stale :anomaly/category"
    (let [a {:anomaly/subtype :anomalies/timeout
             :anomaly/category :anomalies/unavailable
             :anomaly/message "msg"}]
      (is (= (response/anomaly->http-status :anomalies/timeout)
             (response/anomaly->http-status (or (:anomaly/subtype a)
                                                (:anomaly/category a))))))))

(deftest http-response-uses-subtype
  (testing "anomaly->http-response routes by subtype after the flip and by
            category before it, yielding identical status + body shape"
    (let [legacy (response/anomaly->http-response
                  (legacy-anomaly :anomalies/forbidden))
          canonical (response/anomaly->http-response
                     (canonical-anomaly :unauthorized :anomalies/forbidden))]
      (is (= (:status legacy) (:status canonical)))
      (is (= (get-in legacy [:body :error :code])
             (get-in canonical [:body :error :code]))))))

(deftest log-data-carries-classification-via-subtype
  (testing "log-data emits the classification under the legacy
            :anomaly/category key (wire shape preserved for downstream log
            consumers) but the VALUE comes from :anomaly/subtype after the
            flip — so the log payload is unchanged"
    (let [legacy (response/anomaly->log-data
                  (legacy-anomaly :anomalies/timeout))
          canonical (response/anomaly->log-data
                     (canonical-anomaly :timeout :anomalies/timeout))]
      (is (= :anomalies/timeout (:anomaly/category legacy)))
      (is (= :anomalies/timeout (:anomaly/category canonical))
          "subtype value flows into the legacy :anomaly/category output key"))))

(deftest event-data-anomaly-code-reads-subtype
  (testing ":anomaly-code in event-data reflects the subtype after the flip"
    (is (= :anomalies/forbidden
           (:anomaly-code (response/anomaly->event-data
                           (canonical-anomaly :unauthorized
                                              :anomalies/forbidden)))))))

(deftest outcome-evidence-anomaly-code-reads-subtype
  (testing ":outcome/anomaly-code in evidence reflects the subtype after the
            flip"
    (is (= :anomalies/unavailable
           (:outcome/anomaly-code
            (response/anomaly->outcome-evidence
             (canonical-anomaly :unavailable :anomalies/unavailable)))))))

;; ---------------------------------------------------------------------------- Domain-payload dual-shape reads

(deftest log-data-reads-gate-errors-from-both-shapes
  (testing "anomaly->log-data finds :anomaly.gate/errors whether they live at
            the legacy top level or under :anomaly/data after the flatten"
    (let [errors [{:e 1} {:e 2}]
          legacy (legacy-payload :anomalies.gate/validation-failed "gate"
                                 {:anomaly.gate/errors errors})
          canonical (canonical-payload :invalid-input
                                       :anomalies.gate/validation-failed
                                       "gate"
                                       {:gate/errors errors})]
      (is (= (count errors) (:anomaly.gate/error-count (response/anomaly->log-data legacy))))
      (is (= (count errors) (:anomaly.gate/error-count (response/anomaly->log-data canonical)))))))

(deftest log-data-reads-agent-role-from-both-shapes
  (testing "anomaly->log-data finds :anomaly.agent/role on either shape"
    (let [legacy (legacy-payload :anomalies.agent/tool-loop "loop"
                                 {:anomaly.agent/role :implementer})
          canonical (canonical-payload :exhausted :anomalies.agent/tool-loop
                                       "loop"
                                       {:agent/role :implementer})]
      (is (= :implementer (:anomaly.agent/role (response/anomaly->log-data legacy))))
      (is (= :implementer (:anomaly.agent/role (response/anomaly->log-data canonical)))))))

(deftest outcome-evidence-reads-domain-payload-from-both-shapes
  (testing "anomaly->outcome-evidence :outcome/error-details carries gate /
            agent / llm / repair payload regardless of whether the producer
            emitted them at the top level or under :anomaly/data"
    (let [legacy (legacy-payload :anomalies.repair/repair-failed "boom"
                                 {:anomaly.gate/errors [{:e 1}]
                                  :anomaly.agent/role :reviewer
                                  :anomaly.llm/model "gpt-4o"
                                  :anomaly.repair/strategy :rebuild
                                  :anomaly.repair/attempts 3})
          canonical (canonical-payload :fault :anomalies.repair/repair-failed
                                       "boom"
                                       {:gate/errors [{:e 1}]
                                        :agent/role :reviewer
                                        :llm/model "gpt-4o"
                                        :repair/strategy :rebuild
                                        :repair/attempts 3})
          legacy-details (:outcome/error-details (response/anomaly->outcome-evidence legacy))
          canonical-details (:outcome/error-details (response/anomaly->outcome-evidence canonical))]
      (doseq [k [:anomaly.gate/errors :anomaly.agent/role :anomaly.llm/model
                 :anomaly.repair/strategy :anomaly.repair/attempts]]
        (is (= (get legacy-details k) (get canonical-details k))
            (str k " survives the flatten via :anomaly/data bridge"))))))

(deftest outcome-evidence-omits-domain-payload-when-absent
  (testing "domain keys are only emitted when present in either shape — no
            spurious nils in :outcome/error-details when the anomaly carries
            no domain payload at all"
    (let [details (:outcome/error-details
                   (response/anomaly->outcome-evidence
                    {:anomaly/type :fault :anomaly/message "x"}))]
      (is (not (contains? details :anomaly.gate/errors)))
      (is (not (contains? details :anomaly.agent/role)))
      (is (not (contains? details :anomaly.llm/model))))))
