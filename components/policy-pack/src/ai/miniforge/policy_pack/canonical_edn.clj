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
(ns ai.miniforge.policy-pack.canonical-edn
  "Canonical pack serialization (N4 §8.1.1) — the exact bytes a signature is
   computed over and the pack content hash of §5.5 digests.

   Layer 0: Rendering constants
   Layer 1: Canonical rendering
   Layer 2: Pack signable bytes"
  (:require
   [ai.miniforge.policy-pack.canonical-order :as order]
   [clojure.string :as str])
  (:import
   [java.time Instant ZoneOffset]
   [java.time.format DateTimeFormatter]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private iso-millis
  ;; Fixed pattern, not Instant/toString, which varies precision with value.
  (.withZone (DateTimeFormatter/ofPattern "yyyy-MM-dd'T'HH:mm:ss.SSS'Z'")
             ZoneOffset/UTC))

(def ^{:stratum 0} ^:private unrenderable-prefix
  "Print prefix of a value with no EDN reader form."
  "#object[")

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} canonical-edn
  "Render a value as canonical EDN per N4 §8.1.1: maps key-ordered at every
   depth, sets as sorted vectors, sequences in declared order, instants as
   millisecond-precision UTC, single spaces between entries.

   Throws on a value with no EDN reader form — a live `:rule/check-fn`
   rather than the symbol §8.1.1 requires. Its print form carries an
   identity hash, so the bytes would differ on every run."
  ^String [v]
  (let [cmp (partial order/canonical-compare canonical-edn)]
    (cond
      (map? v)
      (str "{"
           (str/join " "
                     (map (fn [[k value]]
                            (str (canonical-edn k) " " (canonical-edn value)))
                          (sort-by key cmp v)))
           "}")

      (set? v)
      (str "[" (str/join " " (map canonical-edn (sort cmp v))) "]")

      (vector? v)
      (str "[" (str/join " " (map canonical-edn v)) "]")

      (sequential? v)
      (str "(" (str/join " " (map canonical-edn v)) ")")

      (instance? Instant v)
      (str "#inst \"" (.format iso-millis ^Instant v) "\"")

      (instance? Date v)
      (str "#inst \"" (.format iso-millis (.toInstant ^Date v)) "\"")

      :else
      (let [printed (pr-str v)]
        (if (str/starts-with? printed unrenderable-prefix)
          (throw (ex-info "Pack value has no EDN reader form and cannot be signed"
                          {:policy-pack/value-type (some-> v class .getName)}))
          printed)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} pack-signable-bytes
  "Signable bytes of a pack manifest (N4 §8.1.1): signature fields stripped,
   remainder rendered as canonical EDN in UTF-8. Ed25519 signs these
   directly; the §5.5 pack content hash digests the same bytes."
  [pack]
  (-> pack
      (dissoc :pack/signature :pack/signed-by :pack/signed-at)
      canonical-edn
      (.getBytes "UTF-8")))
