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

(ns ai.miniforge.content-hash.core
  "Implementation of content hashing primitives.

   Domain-free: no SDLC, workflow, or evidence schema is referenced here.
   Computes a canonical EDN serialization (with deterministically ordered map
   keys) and a SHA-256 digest over that serialization.")

;------------------------------------------------------------------------------ Layer 0
;; Comparator

(defn- type-rank
  "Rank disparate map-key types so canonical EDN can sort heterogeneous keys
   without throwing. Lower ranks sort first."
  [x]
  (cond
    (nil? x) 0
    (boolean? x) 1
    (number? x) 2
    (char? x) 3
    (string? x) 4
    (keyword? x) 5
    (symbol? x) 6
    (uuid? x) 7
    (instance? java.util.Date x) 8
    (instance? java.time.Instant x) 9
    :else 10))

(defn- canonical-compare
  "Total-order comparator for canonical EDN. Same-type values use natural
   compare; cross-type values fall back to string representation comparison
   under a stable type rank."
  [a b]
  (let [ra (type-rank a)
        rb (type-rank b)]
    (if (not= ra rb)
      (compare ra rb)
      (try
        (compare a b)
        (catch Exception _
          (compare (pr-str a) (pr-str b)))))))

;------------------------------------------------------------------------------ Layer 1
;; Canonical EDN

(declare ->canonical)

(defn- canonicalize-entry
  "Canonicalize the value of a key/value pair, leaving the key unchanged."
  [[k v]]
  [k (->canonical v)])

(defn- canonicalize-map
  "Recursively canonicalize a map by sorting keys and canonicalizing values."
  [m]
  (into (sorted-map-by canonical-compare)
        (map canonicalize-entry)
        m))

(defn- canonicalize-coll
  "Recursively canonicalize a non-map collection by canonicalizing each
   element. Order is preserved for sequential collections; sets are sorted
   under the canonical comparator."
  [c]
  (cond
    (set? c) (into (sorted-set-by canonical-compare)
                   (map ->canonical)
                   c)
    (map-entry? c) [(->canonical (key c)) (->canonical (val c))]
    :else (mapv ->canonical c)))

(defn- ->canonical
  "Walk a value into a canonical form: maps become sorted-maps, sets become
   sorted-sets, sequential collections become vectors. Scalars pass through."
  [x]
  (cond
    (map? x) (canonicalize-map x)
    (coll? x) (canonicalize-coll x)
    :else x))

(defn canonical-edn
  "Return a deterministic EDN string for `x`.

   Maps are emitted with keys sorted under a stable comparator; sets are
   sorted; sequential collections preserve order. The output is suitable as
   input to a content hash because logically equal values produce identical
   strings regardless of original insertion order."
  [x]
  (pr-str (->canonical x)))

;------------------------------------------------------------------------------ Layer 2
;; SHA-256 digest

(defn- sha256-hex
  "Compute the SHA-256 digest of the given UTF-8 string and return it as a
   lowercase hex string (64 characters)."
  [s]
  (let [digest (java.security.MessageDigest/getInstance "SHA-256")
        bytes  (.digest digest (.getBytes ^String s "UTF-8"))]
    (apply str (map #(format "%02x" %) bytes))))

(defn content-hash
  "Compute the SHA-256 digest of the canonical EDN of `x`.

   Returns a lowercase 64-character hex string. Logically equal values
   (e.g. maps with identical keys/values regardless of insertion order)
   produce identical hashes."
  [x]
  (sha256-hex (canonical-edn x)))
