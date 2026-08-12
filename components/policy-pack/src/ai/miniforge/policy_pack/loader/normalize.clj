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
(ns ai.miniforge.policy-pack.loader.normalize
  "Rule and pack normalization: fill required-field defaults and coerce
   collection types. Extracted from `ai.miniforge.policy-pack.loader`
   (rule 210: the combined namespace measured 5 real layers, max 3)."
  (:require
   [ai.miniforge.policy-pack.loader.timestamps :as timestamps]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} normalize-rule
  "Normalize a rule, ensuring required fields and types."
  [rule]
  (cond-> rule
    (not (:rule/applies-to rule))
    (assoc :rule/applies-to {})

    (get-in rule [:rule/applies-to :task-types])
    (update-in [:rule/applies-to :task-types] #(if (set? %) % (set %)))

    (get-in rule [:rule/applies-to :repo-types])
    (update-in [:rule/applies-to :repo-types] #(if (set? %) % (set %)))

    (get-in rule [:rule/applies-to :phases])
    (update-in [:rule/applies-to :phases] #(if (set? %) % (set %)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} normalize-pack
  "Normalize a pack, ensuring required fields and types."
  [pack]
  (-> pack
      (update :pack/rules #(mapv normalize-rule (or % [])))
      (update :pack/categories #(or % []))
      (timestamps/map-instant-keys timestamps/ensure-instant)
      ;; Default trust metadata
      (update :pack/trust-level #(or % :untrusted))
      (update :pack/authority #(or % :authority/data))
      (update :pack/dependencies #(or % []))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (normalize-rule {:rule/id :test
                   :rule/title "Test"
                   :rule/description "Desc"
                   :rule/severity :high
                   :rule/category "300"
                   :rule/applies-to {:task-types [:import]}  ; vector, not set
                   :rule/detection {:type :content-scan}
                   :rule/enforcement {:action :warn :message "Warning"}})

  :leave-this-here)
