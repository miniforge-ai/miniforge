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
  "Canonical EDN for manifests, digests and journal records. Map entries and
   set elements are written in the order of their own canonical text, so a
   value encodes, and hashes, the same however it was built. No sorted
   collection is ever built: ordering never has to agree with Clojure
   equality, and no entry can be merged or dropped."
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.deliberation-harness.messages :as messages]
   [clojure.edn :as edn]
   [clojure.string :as str])
  (:import
   (java.io PushbackReader StringReader)
   (java.math BigInteger)
   (java.security MessageDigest)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} digest-algorithm "SHA-256")

(deftype ^{:stratum 0} RejectedTag [tag])

(defn- ^{:stratum 0} stably
  "Call `f` with fixed, readable, untruncated printer settings, so a caller's
   bindings cannot change an encoding."
  [f]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-dup* false *print-readably* true *print-namespace-maps* false]
    (f)))

;; The plain Clojure view of a value that prints like a plain collection but
;; is not one — a record, a host map or collection, an array — or nil. Such a
;; value is encoded with its class tag, so it never shares a digest with the
;; plain value it resembles.
(defn- ^{:stratum 0} host-view [value]
  (cond
    (record? value) (into {} value)
    (and (instance? java.util.Map value) (not (map? value))) (into {} value)
    (and (instance? java.util.Collection value) (not (coll? value))) (vec value)
    (some-> value class .isArray) (vec value)
    :else nil))

(defn- ^{:stratum 0} emit
  "The canonical text of `value`: maps, sets, vectors and lists written
   element by element, map entries ordered by their key's then value's text
   and set elements by their own text. A record, host collection or array is
   tagged with its class (`host-view`), so it never shares a text with the
   plain value it resembles; a float is written as the exact
   double it widens to, so it never shares a text with a different double.
   Anything else prints as itself. Call it inside `stably`."
  [value]
  (let [joined (fn [open texts close] (str open (str/join " " texts) close))]
    (cond
      (host-view value) (str "#" (.getName (class value)) (emit (host-view value)))
      (instance? Float value) (pr-str (double value))
      (map? value) (joined "{" (->> value
                                    (map (fn [[k v]] [(emit k) (emit v)]))
                                    sort
                                    (map (fn [[k v]] (str k " " v))))
                           "}")
      (set? value) (joined "#{" (sort (map emit value)) "}")
      (vector? value) (joined "[" (map emit value) "]")
      (seq? value) (joined "(" (map emit value) ")")
      :else (pr-str value))))

(defn- ^{:stratum 0} mark-tag
  "EDN reader for any custom tag. Journals and manifests carry plain data
   (EDN's built-in `#inst` and `#uuid` included), so a custom-tagged value
   becomes a marker plain EDN cannot construct, which `read-text` refuses."
  [tag _value]
  (->RejectedTag tag))

(defn- ^{:stratum 0} rejected-tag [value]
  (some #(when (instance? RejectedTag %) (.-tag ^RejectedTag %))
        (tree-seq coll? seq value)))

(defn- ^{:stratum 0} attempt
  "Call `f`, which returns `[:ok value]` or `[:failure anomaly]`. A throw
   becomes `[:failure anomaly]` of `type` carrying `message`, `data` and the
   exception's provenance. Success is tracked by the tag, never by the
   value's shape, so parsed data that looks like an anomaly stays data.
   Plain `try`: this component has no slingshot dependency and this is its
   one catch (dewey 211, exemption 1)."
  [f type message data]
  (try
    (f)
    (catch Exception e
      [:failure (anomaly/exception-anomaly type message data e)])))

(defn- ^{:stratum 0} single-form
  "`[:ok form]` for the one EDN form in `text`, or `[:failure anomaly]`
   when there is none or more."
  [^String text]
  (let [reader (PushbackReader. (StringReader. text))
        eof (Object.)
        options {:readers {} :default mark-tag :eof eof}
        value (edn/read options reader)]
    (if (and (not (identical? eof value)) (identical? eof (edn/read options reader)))
      [:ok value]
      [:failure (anomaly/anomaly :invalid-input (messages/system-t :anomaly/not-one-form) {})])))

(defn- ^{:stratum 0} with-source [[status value :as result] source]
  (if (= :failure status)
    [:failure (assoc-in value [:anomaly/data :source] (str source))]
    result))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} encode
  "The canonical EDN text of `value`. Values EDN cannot tell apart (a float
   and a double that print alike) share a text; `plain?` refuses them in a
   journal payload."
  [value]
  (stably #(emit value)))

(defn ^{:stratum 1} digest
  "Lowercase hex SHA-256 of `value`'s canonical encoding."
  [value]
  (let [bytes (.getBytes ^String (encode value) "UTF-8")
        hashed (.digest (MessageDigest/getInstance digest-algorithm) bytes)]
    (format "%064x" (BigInteger. 1 hashed))))

(defn- ^{:stratum 1} parse
  "`[:ok value]` for `text` holding exactly one plain EDN form, otherwise
   `[:failure anomaly]`: unparseable, not one form, or a custom reader tag."
  [text]
  (let [[status value :as result]
        (attempt #(single-form text) :invalid-input (messages/system-t :anomaly/unreadable-edn) {})
        tag (when (= :ok status) (rejected-tag value))]
    (if tag
      [:failure (anomaly/anomaly :invalid-input (messages/system-t :anomaly/unknown-tag) {:tag tag})]
      result)))

(defn ^{:stratum 1} parse-source
  "`[:ok value]` for the one plain EDN form in the UTF-8 text of `source`,
   or `[:failure anomaly]` naming the source when reading or parsing fails.
   Callers that must tell data from failure use this, not `read-source`."
  [source]
  (let [[status text :as read]
        (attempt #(vector :ok (slurp source :encoding "UTF-8")) :unavailable
                 (messages/system-t :anomaly/unreadable-source) {})]
    (with-source (if (= :ok status) (parse text) read) source)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-text
  "Parse `text` as exactly one plain EDN form, or return an `:invalid-input`
   anomaly for text that does not parse, holds other than one form, or
   carries a custom reader tag."
  [text]
  (second (parse text)))

(defn ^{:stratum 2} read-source
  "Read `source` (a file or resource URL) as UTF-8 and parse it, or return
   an anomaly naming the source when it cannot be read or parsed."
  [source]
  (second (parse-source source)))

(defn ^{:stratum 2} plain?
  "True when `value` survives an encode and read unchanged, in value and in
   encoding, so a record written from it replays to the same hash. False
   for objects EDN cannot carry (an `Instant`, a function), for values that
   print ambiguously, and for host collections such as a `LinkedHashMap`
   whose key order canonicalization does not fix."
  [value]
  (let [encoded (encode value)
        [status read-back] (parse encoded)]
    (and (= :ok status)
         (= value read-back)
         (= encoded (encode read-back)))))
