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
   [clojure.java.io :as io]
   [clojure.string :as str])
  (:import
   (java.io PushbackReader StringReader)
   (java.math BigInteger)
   (java.nio ByteBuffer)
   (java.nio.charset CodingErrorAction StandardCharsets)
   (java.security MessageDigest)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} digest-algorithm
  "SHA-256: collision-resistant and present in every JDK, so a digest computed
   on one host verifies on any other."
  "SHA-256")

(deftype ^{:stratum 0} RejectedTag [tag])

;; Call `f` with fixed, readable, untruncated printer settings, so a caller's
;; bindings cannot change an encoding.
(defn- ^{:stratum 0} stably
  [f]
  (binding [*print-length* nil *print-level* nil *print-meta* false
            *print-dup* false *print-readably* true *print-namespace-maps* false]
    (f)))

;; "#<class>" for a value that prints like a plain collection but is not one
;; (a record, a host map, set or collection, an array), else "". The tag keeps
;; it from sharing a digest with the plain value it resembles.
(defn- ^{:stratum 0} host-tag [value]
  (if (or (record? value)
          (and (instance? java.util.Map value) (not (map? value)))
          (and (instance? java.util.Collection value) (not (coll? value)))
          (some-> value class .isArray))
    (str "#" (.getName (class value)))
    ""))

;; The canonical text of `value`, written from its own entries and never
;; converted to another collection, so no two entries ever merge. Map entries
;; are ordered by their key's then value's text, set elements by their own
;; text; sequences keep their order. Every member of a host collection carries
;; its class, since Java equality tells `(int 1)` from `1`. A float is written as the exact double
;; it widens to. Anything else prints as itself. Call it inside `stably`.
(defn- ^{:stratum 0} emit [value]
  (let [tag (host-tag value)
        joined (fn [open texts close] (str tag open (str/join " " texts) close))
        member (fn [k] (str (when (seq tag) (str (some-> k class .getName) " ")) (emit k)))]
    (cond
      (instance? Float value) (pr-str (double value))
      (instance? java.util.Map value)
      (joined "{" (->> value (map (fn [[k v]] [(member k) (member v)])) sort (map #(str/join " " %))) "}")
      (instance? java.util.Set value) (joined "#{" (sort (map member value)) "}")
      (seq? value) (joined "(" (map emit value) ")")
      (or (instance? java.util.Collection value) (some-> value class .isArray))
      (joined "[" (map member value) "]")
      :else (pr-str value))))

;; EDN reader for any custom tag. Journals and manifests carry plain data
;; (EDN's built-in `#inst` and `#uuid` included), so a custom-tagged value
;; becomes a marker plain EDN cannot construct, which `read-text` refuses.
(defn- ^{:stratum 0} mark-tag
  [tag _value]
  (->RejectedTag tag))

(defn- ^{:stratum 0} rejected-tag [value]
  (some #(when (instance? RejectedTag %) (.-tag ^RejectedTag %))
        (tree-seq coll? seq value)))

;; Call `f`, which returns `[:ok value]` or `[:failure anomaly]`. A throw
;; becomes `[:failure anomaly]` of `type` carrying `message`, `data` and the
;; exception's provenance. Success is tracked by the tag, never by the
;; value's shape, so parsed data that looks like an anomaly stays data.
;; Plain `try`: this component has no slingshot dependency and this is its
;; one catch (dewey 211, exemption 1).
(defn- ^{:stratum 0} attempt
  [f type message data]
  (try
    (f)
    (catch Exception e
      [:failure (anomaly/exception-anomaly type message data e)])))

;; `[:ok form]` for the one EDN form in `text`, or `[:failure anomaly]`
;; when there is none or more.
(defn- ^{:stratum 0} single-form
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
  "The canonical EDN text of `value`. A float is written as the exact double it
   widens to, so it shares a text only with that double, and replays as it."
  [value]
  (stably #(emit value)))

;; Lowercase hex SHA-256 of `value`'s canonical encoding, hashed as UTF-16
;; code units so that no two Java strings (even ones holding unpaired
;; surrogates, which UTF-8 cannot carry) share a digest.
(defn ^{:stratum 1} digest [value]
  (let [text ^String (encode value)
        bytes (.array (doto (java.nio.ByteBuffer/allocate (* 2 (count text)))
                        (-> .asCharBuffer (.put text))))
        hashed (.digest (MessageDigest/getInstance digest-algorithm) bytes)]
    (format "%064x" (BigInteger. 1 hashed))))

;; The text of `source` decoded as strict UTF-8: malformed bytes throw rather
;; than becoming U+FFFD, so two different files never read as the same text.
(defn- ^{:stratum 0} strict-utf8 [source]
  (let [decoder (doto (.newDecoder StandardCharsets/UTF_8)
                  (.onMalformedInput CodingErrorAction/REPORT)
                  (.onUnmappableCharacter CodingErrorAction/REPORT))]
    (str (.decode decoder (ByteBuffer/wrap (with-open [in (io/input-stream source)] (.readAllBytes in)))))))

;; `[:ok value]` for `text` holding exactly one plain EDN form, otherwise
;; `[:failure anomaly]`: unparseable, not one form, or a custom reader tag.
(defn- ^{:stratum 1} parse
  [text]
  (let [[status value :as result]
        (attempt #(single-form text) :invalid-input (messages/system-t :anomaly/unreadable-edn) {})
        tag (when (= :ok status) (rejected-tag value))]
    (if tag
      [:failure (anomaly/anomaly :invalid-input (messages/system-t :anomaly/unknown-tag) {:tag tag})]
      result)))

(defn ^{:stratum 1} parse-source
  "`[:ok value]` for the one plain EDN form in strict UTF-8 `source`, or
   `[:failure anomaly]` naming it; use this, not `read-source`, to tell data from failure."
  [source]
  (let [[status text :as read]
        (attempt #(vector :ok (strict-utf8 source)) :unavailable
                 (messages/system-t :anomaly/unreadable-source) {})]
    (with-source (if (= :ok status) (parse text) read) source)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} read-text
  "Parse `text` as exactly one plain EDN form, or an `:invalid-input` anomaly."
  [text]
  (second (parse text)))

(defn ^{:stratum 2} read-source
  "Parse UTF-8 `source` (file or resource URL), or an anomaly naming it."
  [source]
  (second (parse-source source)))

;; True when `value` survives an encode, a UTF-8 file and a read unchanged,
;; in value and in encoding, so a record written from it replays to the same
;; hash. False for objects EDN cannot carry (an `Instant`, a function), for
;; text UTF-8 cannot hold, and for host collections such as a
;; `LinkedHashMap`, whose replay is a different value. Host collections are
;; refused before encoding, so a self-referential one cannot recurse forever.
(defn ^{:stratum 2} plain? [value]
  (and (not-any? #(seq (host-tag %)) (tree-seq coll? seq value))
       (let [encoded (encode value)
             [status read-back] (parse encoded)]
         (and (= :ok status)
              (= encoded (String. (.getBytes ^String encoded "UTF-8") "UTF-8"))
              (= value read-back)
              (= encoded (encode read-back))))))
