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
(ns ai.miniforge.pipeline-config.resolver-test
  "Quality-rule resolution keeps what it cannot resolve, as data."
  (:require [clojure.test :refer [deftest is testing]]
            [ai.miniforge.pipeline-config.interface :as pc]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures
(def ^{:stratum 0} ^:private unresolvable-descriptors
  [{:rule/type :custom :rule/id :no-such-rule}
   {:rule/type :unknown-type :rule/field :name}])

(defn- ^{:stratum 0} validate-pipeline-edn
  "A one-stage pipeline EDN whose validate stage names `rules`."
  [rules]
  {:pipeline/name "Rule resolution"
   :pipeline/version "1.0.0"
   :pipeline/mode :full-refresh
   :pipeline/stages [{:stage/name "Validate"
                      :stage/family :validate
                      :stage/input-datasets [:ds/in]
                      :stage/output-datasets [:ds/out]
                      :stage/dependencies []
                      :stage/config {:stage/quality-pack {:pack/id :test-pack
                                                          :pack/rules rules}}}]
   :pipeline/input-datasets []
   :pipeline/output-datasets []})

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} resolved-rules
  "Resolve `rules` against the built-in rule registry; return the hydrated rules."
  [rules]
  (let [ctx      (pc/create-resolution-context
                  {:rule-registry (pc/resolve-rules (pc/create-rule-registry))})
        resolved (pc/resolve-pipeline (validate-pipeline-edn rules) ctx)]
    (get-in resolved [:pipeline/stages 0 :stage/config :stage/quality-pack :pack/rules])))

;------------------------------------------------------------------------------ Layer 2

;; Tests
(deftest ^{:stratum 2} test-unresolvable-rules-stay-descriptors
  (testing "given rules the registry cannot resolve → each stays its descriptor, with no check fn"
    (let [rules (resolved-rules unresolvable-descriptors)]
      (is (= unresolvable-descriptors rules))
      (is (not-any? :rule/check-fn rules)))))

(deftest ^{:stratum 2} test-resolvable-rules-hydrate-beside-unresolvable
  (testing "given one built-in and one unknown rule → the built-in hydrates, the unknown stays data"
    (let [[required unknown] (resolved-rules [{:rule/type :required :rule/field :name}
                                              (first unresolvable-descriptors)])]
      (is (fn? (:rule/check-fn required)))
      (is (= (first unresolvable-descriptors) unknown)))))
