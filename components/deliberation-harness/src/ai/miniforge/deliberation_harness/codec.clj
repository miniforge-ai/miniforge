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
(ns ai.miniforge.deliberation-harness.codec
  "Canonical EDN for manifests, digests and journal records: maps and sets
   print in a fixed order, so equal values encode, and hash, identically."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.messages :as messages]
   [clojure.edn :as edn]
   [clojure.walk :as walk])
  (:import
   (java.math BigInteger)
   (java.security MessageDigest)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} digest-algorithm "SHA-256")

(defn- ^{:stratum 0} printed-order [a b]
  (compare (pr-str a) (pr-str b)))

(defn- ^{:stratum 0} ordered [value]
  (cond
    (map? value) (into (sorted-map-by printed-order) value)
    (set? value) (into (sorted-set-by printed-order) value)
    :else value))

(defn- ^{:stratum 0} mark-tag
  "EDN reader for any custom tag. Journals and manifests carry plain data
   (EDN's built-in `#inst` and `#uuid` included), so a custom-tagged value
   becomes a marker that `read-text` refuses."
  [tag _value]
  {::rejected-tag tag})

(defn- ^{:stratum 0} rejected-tag [value]
  (some ::rejected-tag (filter map? (tree-seq coll? seq value))))

(defn- ^{:stratum 0} parse
  "Plain `try`: this component has no slingshot dependency and this is its
   one catch (dewey 211, exemption 1)."
  [text]
  (try
    (edn/read-string {:readers {} :default mark-tag} text)
    (catch Exception e
      (anomaly/exception-anomaly :invalid-input (messages/system-t :anomaly/unreadable-edn) e))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} canonical
  "`value` with every nested map and set in printed-key order."
  [value]
  (walk/postwalk ordered value))

(defn ^{:stratum 1} encode
  "The canonical EDN text of `value`."
  [value]
  (pr-str (canonical value)))

(defn ^{:stratum 1} digest
  "Lowercase hex SHA-256 of `value`'s canonical encoding."
  [value]
  (let [bytes (.getBytes ^String (encode value) "UTF-8")
        hashed (.digest (MessageDigest/getInstance digest-algorithm) bytes)]
    (format "%064x" (BigInteger. 1 hashed))))

(defn ^{:stratum 1} read-text
  "Parse `text` as plain EDN, or return an `:invalid-input` anomaly for text
   that does not parse or carries a custom reader tag."
  [text]
  (let [value (parse text)]
    (cond
      (anomaly/anomaly? value) value
      (rejected-tag value) (anomaly/anomaly :invalid-input
                                            (messages/system-t :anomaly/unknown-tag)
                                            {:tag (rejected-tag value)})
      :else value)))
