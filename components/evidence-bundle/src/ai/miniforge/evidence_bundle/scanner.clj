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

(ns ai.miniforge.evidence-bundle.scanner
  "Sensitive-data scanner for assembled evidence bundles.")

(def ^:private sensitive-patterns
  [{:finding/type :email
    :finding/pattern #"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"}
   {:finding/type :ssn
    :finding/pattern #"\b\d{3}-\d{2}-\d{4}\b"}
   {:finding/type :aws-access-key
    :finding/pattern #"\bAKIA[0-9A-Z]{16}\b"}])

(def ^:private pii-finding-types
  #{:email :ssn})

(defn- bundle-text
  [bundle]
  (binding [*print-length* 1000
            *print-level* 20]
    (pr-str bundle)))

(defn scan-artifact
  "Scan an evidence bundle and return sensitive-data findings.

   The scanner reports finding types only; matched secret values are not
   copied into evidence."
  [bundle]
  (let [text (bundle-text bundle)]
    {:scan/findings
     (vec
      (keep (fn [{:finding/keys [type pattern]}]
              (when (re-find pattern text)
                {:finding/type type}))
            sensitive-patterns))}))

(defn compliance-metadata
  "Convert scan results into evidence compliance metadata."
  [scan-result]
  (let [findings (:scan/findings scan-result)
        contains-pii? (some #(contains? pii-finding-types (:finding/type %)) findings)]
    (if (seq findings)
      (cond-> {:compliance/sensitive-findings findings}
        contains-pii? (assoc :evidence/contains-pii? true))
      {})))
