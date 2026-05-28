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
