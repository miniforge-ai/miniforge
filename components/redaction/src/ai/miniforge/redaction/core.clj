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

(defn- ^{:stratum 0} metadata-values [value]
  (keep meta (tree-seq coll? seq value)))

(defn- ^{:stratum 0} map-target [value]
  ;; A redacted key can change type; its old comparator may reject the marker.
  (if (sorted? value) (into {} value) value))

(defn- ^{:stratum 0} set-target [value]
  (if (sorted? value) #{} (empty value)))

(defn- ^{:stratum 0} free-key
  "Return [unused-key next-suffix], continuing this key's prior search.

   Two different secrets redact to the same marker, so a map keyed by
   both would collapse to one entry and silently drop a value — the
   keys were secret, but the values they held were not. Only runs for a
   key that actually changed, which is rare, so the common path pays
   nothing."
  [m k start]
  (letfn [(nth-form [k n]
            ;; Type-preserving: a map key is compared by value, so the
            ;; only way to keep two entries is to make the keys differ by
            ;; value. Appending to the string form would turn a vector
            ;; key into a string containing a printed vector, so each
            ;; kind grows in its own way instead.
            (cond
              (string? k) (str k policy/disambiguator-separator n)
              (map? k)    (assoc k ::disambiguator n)
              (set? k)    (conj k (str (policy/marker) policy/disambiguator-separator n))
              ;; conj, not (conj (vec k) ...): vec would coerce a seq
              ;; or a queue to a vector, which is the coercion this
              ;; branch exists to avoid. conj adds wherever the type
              ;; adds, so a vector, set and queue each keep their exact
              ;; class and a list gains the element at the front.
              ;;
              ;; Redaction eagerly rebuilds sequences as lists so the result
              ;; remains inspectable and portable without deferred work.
              (coll? k)   (conj k (str (policy/marker) policy/disambiguator-separator n))
              :else       (str k policy/disambiguator-separator n)))]
    (if (contains? m k)
      (first (for [n (iterate inc start)
                   :let [candidate (nth-form k n)]
                   :when (not (contains? m candidate))]
               [candidate (inc n)]))
      [k start])))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} redact
  "Redact X — any nested data structure — per N3 §8.1/§8.2.

   Two rules, applied together:

     1. A value under a secret-naming key is replaced wholesale.
     2. A string containing a secret-shaped value has that value
        replaced in place.

   Map keys keep their names — a key names a field — but a secret
   hiding *in* a key is still removed by shape, and metadata is walked
   like any other value. See `match/redact-key`.

   Covers Clojure data, which is what an N3 event is: §4.3 makes every
   event durable, so anything in one has to survive pr-str and
   edn/read-string. A java.util collection, an array, or an atom is not
   walked and its contents are returned untouched — such a value cannot
   be in a conformant event to begin with, but the limit is real and
   worth naming rather than discovering. `boundary-cases-test` pins it."
  [x]
  (let [result
        (cond
          ;; Rebuilt onto X itself rather than (empty x): records throw
          ;; UnsupportedOperationException on empty, and a record in an event
          ;; payload would crash publish! on the hot path. Assoc'ing every key
          ;; back onto X preserves record/plain map types. Sorted collections
          ;; become hash collections because markers can change key/value types.
          (map? x)
          (first
           (reduce-kv
           (fn [[m suffixes] k v]
             (let [;; A collection can be a key too, and match/redact-key
                   ;; knows only scalars — it cannot recurse without
                   ;; depending on this namespace. Dispatch here, where
                   ;; the recursion already lives.
                   ;;
                   ;; A scalar key needs its metadata walked as well.
                   ;; Only a symbol can carry any — keywords and strings
                   ;; are not IObj — but a symbol key's metadata is as
                   ;; reachable as a value's.
                   k* (if (coll? k)
                        (redact k)
                        (let [rk (match/redact-key k)]
                          (if-let [km (meta k)]
                            (if (instance? clojure.lang.IObj rk)
                              (with-meta rk (redact km))
                              rk)
                            rk)))
                   v* (if (and (match/secret-key? k)
                             (match/redactable-value? v))
                        (policy/marker)
                        (redact v))
                   m (assoc m k v*)]
                 ;; Only touch the key when it actually changed —
                 ;; dissoc/assoc would otherwise reorder an array-map
                 ;; and re-sort a sorted-map for nothing.
                 ;;
                 ;; Identity, not =. Equality ignores metadata at every
                 ;; depth, so a key whose secret sat in the metadata of
                 ;; something nested inside it tested equal to its own
                 ;; redacted form and the original stayed in the map.
                 ;; redact-key returns k itself when nothing changed, so
                 ;; a scalar key still costs nothing.
                 (if (identical? k k*)
                   [m suffixes]
                   (let [m (dissoc m k)
                         cursor-key (if (map? k*) (dissoc k* ::disambiguator) k*)
                         [key next-suffix] (free-key m k* (get suffixes cursor-key 2))]
                     [(assoc m key v*) (assoc suffixes cursor-key next-suffix)]))))
           ;; Each redacted key resumes its search, avoiding quadratic collision work.
           [(map-target x) {}]
           x))

          (vector? x) (mapv redact x)
          (set? x)    (into (set-target x) (map redact) x)

          ;; Materialize a list: no secret-bearing closure or deferred sequence
          ;; may escape into the prepared event or durable codec.
          (seq? x)    (apply list (map redact x))

          ;; Any other Clojure collection. A PersistentQueue is coll? but none
          ;; of map?, vector?, set? or seq?, so without this clause its
          ;; contents pass through untouched — the cond above enumerates
          ;; concrete types, and enumerations of types leak.
          (coll? x)   (into (empty x) (map redact) x)

          (or (number? x) (string? x) (keyword? x) (symbol? x))
          (match/redact-key x)
          :else       x)]
    ;; Metadata is data. pr-str drops it, so a sink that serializes never
    ;; sees it — but the in-memory log and every in-process subscriber
    ;; hold the object itself, and §8.1 is about what is emitted, not
    ;; about what one representation happens to show.
    ;;
    ;; Inline rather than a redact*/redact pair: two mutually recursive
    ;; names are a reference cycle the stratifier rejects (SL007), while
    ;; a single self-recursive function is one node.
    (if-let [m (meta x)]
      (if (instance? clojure.lang.IObj result)
        (with-meta result (redact m))
        result)
      result)))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} clean?
  "True when X carries no value excluded by N3 §8.1. Redaction is
   idempotent, so this is `redact` reaching a fixed point.

   Equality ignores metadata, so check metadata at every reachable node
   separately, including metadata nested within other metadata."
  [x]
  (and (= x (redact x))
       (every? clean? (metadata-values x))))
