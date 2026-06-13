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

(ns ai.miniforge.agent-runtime.error-classifier.schema
  "Malli schemas for the error-classifier pattern-entry shape.

   Strata note: sits above taxonomy (stratum 0). Depends on FailureClass
   from taxonomy. Does not depend on patterns, core, or reporting."
  (:require
   [ai.miniforge.agent-runtime.error-classifier.taxonomy :as taxonomy]))

;;------------------------------------------------------------------------------ Layer 0
;; Workaround sub-schema

(def Workaround
  "Malli schema for an optional workaround entry embedded in a pattern."
  [:map
   [:type                              :keyword]
   [:command                           :string]
   [:description                       :string]
   [:requires-sudo {:optional true}    :boolean]
   [:auto-apply    {:optional true}    :boolean]])

;;------------------------------------------------------------------------------ Layer 1
;; Pattern-entry schema

(def PatternEntry
  "Malli schema for a single error-pattern entry as loaded from EDN config files
   in resources/error-patterns/.

   All keys except :regex are optional — the pattern loader accepts minimal
   entries (only :regex required). Pattern-level :vendor and :type are inherited
   from the file-level config and injected by compile-pattern in patterns.clj.

   The :failure/class key, when present, overrides the file-level :type mapping
   inside resolve-failure-class (see taxonomy/resolve-failure-class)."
  [:map {:closed false}
   [:regex                              :string]
   [:id                {:optional true} :keyword]
   [:vendor            {:optional true} :string]
   [:description       {:optional true} :string]
   [:failure/class     {:optional true} taxonomy/FailureClass]
   [:failure/source    {:optional true} :keyword]
   [:failure/vendor    {:optional true} :keyword]
   [:dependency/class  {:optional true} :keyword]
   [:dependency/retryability {:optional true} :keyword]
   [:rate-limit?       {:optional true} :boolean]
   [:workaround        {:optional true} Workaround]
   [:docs-url          {:optional true} :string]])

(def PatternConfig
  "Malli schema for a top-level error-pattern config file (e.g. external.edn).

   The :patterns vector contains PatternEntry maps. :type and :vendor are
   file-level defaults injected into every entry by the pattern loader."
  [:map {:closed false}
   [:type        :keyword]
   [:patterns    [:vector PatternEntry]]
   [:description {:optional true} :string]
   [:vendor      {:optional true} :string]])

;;------------------------------------------------------------------------------ Rich comment
(comment
  (require '[malli.core :as m])

  ;; Minimal valid pattern entry
  (m/validate PatternEntry {:regex "rate limit"})
  ;; => true

  ;; With explicit failure/class
  (m/validate PatternEntry {:regex "rate limit"
                            :failure/class :failure.class/rate-limit})
  ;; => true

  ;; Invalid failure/class value
  (m/validate PatternEntry {:regex "rate limit"
                            :failure/class :old/rate-limit})
  ;; => false

  ;; Full entry
  (m/validate PatternEntry
              {:id :anthropic-rate-limit
               :regex "(anthropic|claude).*(rate limit|429)"
               :vendor "Anthropic"
               :description "Anthropic provider rate limit"
               :failure/class :failure.class/rate-limit
               :failure/source :external-provider
               :failure/vendor :anthropic
               :dependency/class :rate-limit
               :dependency/retryability :retryable})
  ;; => true
  )
