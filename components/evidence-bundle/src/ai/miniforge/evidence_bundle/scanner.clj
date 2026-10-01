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
  "Sensitive-data scanner for assembled evidence bundles."
  (:require
   [ai.miniforge.redaction.interface :as redaction]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private sensitive-patterns
  [{:finding/type :email
    :finding/pattern #"[A-Za-z0-9._%+-]+@[A-Za-z0-9.-]+\.[A-Za-z]{2,}"}
   {:finding/type :ssn
    :finding/pattern #"\b\d{3}-\d{2}-\d{4}\b"}
   {:finding/type :aws-access-key
    :finding/pattern #"\bAKIA[0-9A-Z]{16}\b"}])

(def ^{:stratum 0} ^:private pii-finding-types
  #{:email :ssn :payment-card})

(def ^{:stratum 0} ^:private secret-finding-types
  #{:aws-access-key :embedded-secret :payment-card})

(defn- ^{:stratum 0} bundle-text
  "Render BUNDLE for pattern matching.

   Bounded by print-length and print-level, so detection sees a truncated
   view of a deep or wide bundle. That makes the findings best-effort
   metadata, not a security boundary: redaction walks the whole structure
   and does not share this limit, so a secret past level 20 is still
   removed even when nothing reports it."
  [bundle]
  (binding [*print-length* 1000
            *print-level* 20]
    (pr-str bundle)))

(defn- ^{:stratum 0} pattern-finding [text {:finding/keys [type pattern]}]
  (when (re-find pattern text)
    {:finding/type type}))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} named-findings [text]
  (let [labelled (into [] (keep (partial pattern-finding text)) sensitive-patterns)]
    (cond-> labelled
      (redaction/payment-card? text) (conj {:finding/type :payment-card}))))

(defn- ^{:stratum 1} include-unnamed-secret [text labelled]
  ;; N6.SD.3 shares N3's definition of a secret. This fallback avoids a
  ;; duplicate label when a named finding already establishes sensitivity;
  ;; redaction remains unconditional and walks the complete value.
  (cond-> labelled
    (and (redaction/secret-string? text)
         (not-any? #(contains? secret-finding-types (:finding/type %)) labelled))
    (conj {:finding/type :embedded-secret})))

(defn ^{:stratum 1} compliance-metadata
  "Convert scan results into evidence compliance metadata."
  [scan-result]
  (let [findings (:scan/findings scan-result)
        contains-pii? (some #(contains? pii-finding-types (:finding/type %)) findings)]
    (if (seq findings)
      (cond-> {:compliance/sensitive-findings findings}
        contains-pii? (assoc :evidence/contains-pii? true))
      {})))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} scan-artifact
  "Scan an evidence bundle and return finding types, never matched values."
  [bundle]
  (let [text (bundle-text bundle)
        labelled (named-findings text)
        findings (include-unnamed-secret text labelled)]
    {:scan/findings findings}))

(comment
  (scan-artifact {}))
