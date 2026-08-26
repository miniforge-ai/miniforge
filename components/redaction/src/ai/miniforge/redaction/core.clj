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
(ns ai.miniforge.redaction.core
  "Walking a value and replacing what N3 §8.1 excludes.

   Redaction happens at construction, not at delivery: §8.1 is a
   MUST NOT on emission, so a redacting sink does not make a
   secret-bearing event conformant — by then it is already sequenced
   and durable."
  (:require
   [ai.miniforge.redaction.match :as match]
   [ai.miniforge.redaction.policy :as policy]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} redact
  "Redact X — any nested data structure — per N3 §8.1/§8.2.

   Two rules, applied together:

     1. A value under a secret-naming key is replaced wholesale.
     2. A string containing a secret-shaped value has that value
        replaced in place.

   Map keys are not redacted: a key names a field, and losing the name
   would hide that the field existed at all."
  [x]
  (cond
    ;; Rebuilt onto X itself rather than (empty x): records throw
    ;; UnsupportedOperationException on empty, and a record in an event
    ;; payload would crash publish! on the hot path. Assoc'ing every key
    ;; back onto X preserves the type — record, sorted map, or plain.
    (map? x)
    (reduce-kv (fn [m k v]
                 (assoc m k (if (and (match/secret-key? k)
                                     (match/redactable-value? v))
                              (policy/marker)
                              (redact v))))
               x
               x)

    (vector? x) (mapv redact x)
    (set? x)    (into (empty x) (map redact) x)

    ;; doall, not a bare map: a lazy seq would defer redaction and keep
    ;; the un-redacted value alive in the closure, so the secret would
    ;; still be reachable from an event §8.1 calls conformant.
    (seq? x)    (doall (map redact x))

    ;; Any other Clojure collection. A PersistentQueue is coll? but none
    ;; of map?, vector?, set? or seq?, so without this clause its
    ;; contents pass through untouched — the cond above enumerates
    ;; concrete types, and enumerations of types leak.
    (coll? x)   (into (empty x) (map redact) x)

    (string? x) (match/redact-string x)
    :else       x))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} clean?
  "True when X carries no value excluded by N3 §8.1. Redaction is
   idempotent, so this is `redact` reaching a fixed point."
  [x]
  (= x (redact x)))
