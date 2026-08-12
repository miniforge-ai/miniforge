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
(ns ai.miniforge.policy-pack.overlay-test.fixtures
  "Shared test data for the `overlay-test` sibling namespaces (rule 210: the
   original `overlay_test.clj` measured 4 real layers, over the 3-layer
   budget; these fixtures are the layer-coherent common base every sibling
   test file requires). Rule construction, pack construction, and the
   base/extra pack instances used across inheritance, collision, override,
   and taxonomy-ref coverage.")

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} now (java.time.Instant/now))

(defn ^{:stratum 0} make-rule [id severity]
  {:rule/id          id
   :rule/title       (name id)
   :rule/description (str "Rule " (name id))
   :rule/severity    severity
   :rule/category    "200"
   :rule/applies-to  {:phases #{:implement :review}}
   :rule/detection   {:type :content-scan :pattern "test"}
   :rule/enforcement {:action :warn :message "warning"}})

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} make-pack [id rules & {:keys [extends overrides taxonomy-ref]}]
  (cond-> {:pack/id          id
           :pack/name        (str "Pack " id)
           :pack/version     "1.0.0"
           :pack/description "Test pack"
           :pack/author      "test"
           :pack/categories  []
           :pack/rules       (vec rules)
           :pack/created-at  now
           :pack/updated-at  now}
    extends      (assoc :pack/extends extends)
    overrides    (assoc :pack/overrides overrides)
    taxonomy-ref (assoc :pack/taxonomy-ref taxonomy-ref)))

(def ^{:stratum 1} base-rule-a (make-rule :test/rule-a :low))

(def ^{:stratum 1} base-rule-b (make-rule :test/rule-b :high))

(def ^{:stratum 1} extra-rule-c (make-rule :test/rule-c :info))

;------------------------------------------------------------------------------ Layer 2

(def ^{:stratum 2} base-pack (make-pack "base" [base-rule-a base-rule-b]
                           :taxonomy-ref {:taxonomy/id :miniforge/dewey
                                          :taxonomy/min-version "1.0.0"}))

(def ^{:stratum 2} extra-pack (make-pack "extra" [extra-rule-c]))
