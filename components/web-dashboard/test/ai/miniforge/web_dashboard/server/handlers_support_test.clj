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
(ns ai.miniforge.web-dashboard.server.handlers-support-test
  "Unit coverage for the shared handler helpers."
  (:require
   [ai.miniforge.web-dashboard.server.handlers.support :as support]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} artifact-id-value-preserves-non-uuid-ids
  (testing "a UUID-shaped string becomes the typed UUID"
    (let [u "01234567-89ab-4cde-8f01-23456789abcd"]
      (is (= (parse-uuid u) (support/artifact-id-value u)))
      (is (uuid? (support/artifact-id-value u)))))
  (testing "a non-UUID string is preserved, not collapsed to nil"
    ;; parse-uuid returns nil (never throws) on a non-UUID string; the
    ;; helper must fall back to the original id so a string-keyed store
    ;; can still resolve it.
    (is (= "artifact-42" (support/artifact-id-value "artifact-42")))
    (is (= "" (support/artifact-id-value ""))))
  (testing "a non-string id passes through unchanged"
    (let [u (random-uuid)]
      (is (= u (support/artifact-id-value u))))
    (is (nil? (support/artifact-id-value nil)))))
