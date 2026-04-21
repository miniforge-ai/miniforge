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

(ns ai.miniforge.workflow-resume.schema
  "Malli schemas for workflow-resume inputs + event shape.

   Validation boundaries (Dewey 004):
   - Event replay is an EXTERNAL boundary — events come from disk,
     potentially written by a different miniforge version, potentially
     half-written or corrupt. Schemas filter here.
   - The component's interface is the INTERNAL boundary — callers in
     CLI, HTTP API, dashboard all go through it. Validate once there,
     then trust internally.

   Core stays schema-free — it runs on pre-validated data."
  (:require
   [malli.core :as m]
   [malli.error :as me]))

;------------------------------------------------------------------------------ Layer 0
;; Event shape — minimum fields the component actually reads

(def EventBase
  "Every event we care about has `:event/type` as the discriminator.
   Extractors filter on it; without it, an event has no meaning here."
  [:map {:closed false}
   [:event/type keyword?]])

;------------------------------------------------------------------------------ Layer 1
;; Inputs to the public API

(def ReconstructContextInput
  [:map {:closed false}
   [:events-dir some?]
   [:workflow-id [:or string? uuid?]]])

(def TrimPipelineInput
  [:map {:closed false}
   [:workflow [:map {:closed false}
               [:workflow/pipeline [:vector
                                    [:map {:closed false}
                                     [:phase keyword?]]]]]]
   [:completed-phases [:sequential keyword?]]])

(def ResolveWorkflowIdentityInput
  [:map {:closed false}
   [:reconstructed [:map {:closed false}
                    [:workflow-spec {:optional true}
                     [:maybe [:map {:closed false}]]]]]
   [:fallback-fn fn?]])

;------------------------------------------------------------------------------ Layer 1
;; Validation helpers

(defn valid-event?
  "True when `ev` is a map with a keyword `:event/type`. Used by the
   reader to filter events that were parseable-as-JSON but shaped
   wrong (e.g. from an older miniforge with a different event format)."
  [ev]
  (m/validate EventBase ev))

(defn validate!
  "Throw ex-info if `value` doesn't match `schema`. Returns `value`
   unchanged on success, so it composes cleanly into threading
   pipelines."
  [schema value opts]
  (if (m/validate schema value)
    value
    (throw (ex-info (get opts :message "Invalid input")
                    (merge {:schema (m/form schema)
                            :value value
                            :errors (me/humanize (m/explain schema value))}
                           (dissoc opts :message))))))
