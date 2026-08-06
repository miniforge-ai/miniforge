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
(ns ai.miniforge.event-stream.event-type-registry.audit
  "Machine-readable audit summary derived from registry data."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.event-stream.event-type-registry.data :as data]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} audit-summary
  (let [registry data/event-type-registry]
    (if (anomaly/anomaly? registry)
      registry
      (let [handled (filter :browser? registry)
            unhandled (remove :browser? registry)
            asymmetries (filter :asymmetry? registry)]
        {:audit/date "2026-08-05"
         :audit/source-server
         "components/event-stream/src/ai/miniforge/event_stream/interface/*.clj"
         :audit/source-browser
         "components/web-dashboard/resources/public/js/app.js"
         :audit/browser-switch "handleWorkflowEvent"
         :total-server-events (count registry)
         :browser-handled-count (count handled)
         :browser-unhandled-count (count unhandled)
         :naming-asymmetry-count (count asymmetries)
         :string-mismatches []
         :coverage-gaps (mapv :json-string unhandled)
         :asymmetries
         (mapv #(select-keys % [:constructor :json-string :asymmetry-note])
               asymmetries)
         :serialisation-rule
         (str "Clojure namespaced keywords serialize as ns/name without a leading "
              "colon; browser comparisons use those plain strings.")}))))
