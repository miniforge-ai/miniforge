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
(ns ai.miniforge.policy-pack.canonical-order
  "Value ordering for canonical pack serialization (N4 §8.1.1).

   Ordering is separate from rendering, and `canonical-compare` takes its
   renderer as an argument, so the two do not depend on each other.

   Layer 0: Byte and type comparison
   Layer 1: Identifier comparison
   Layer 2: The canonical comparator")

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} compare-utf8
  ;; Not String/compareTo, which compares UTF-16 code units and orders
  ;; supplementary characters differently from their UTF-8 bytes.
  [^String a ^String b]
  (let [xs (.getBytes a "UTF-8")
        ys (.getBytes b "UTF-8")
        n  (min (alength xs) (alength ys))]
    (loop [i 0]
      (if (= i n)
        (compare (alength xs) (alength ys))
        (let [d (compare (Byte/toUnsignedInt (aget xs i))
                         (Byte/toUnsignedInt (aget ys i)))]
          (if (zero? d) (recur (inc i)) d))))))

(defn ^{:stratum 0} ^:private type-rank
  ;; §8.1.1 pins the ordering of keys; ranking by type gives a set of
  ;; anything else a total order too.
  [v]
  (cond
    (nil? v)     0
    (boolean? v) 1
    (number? v)  2
    (char? v)    3
    (string? v)  4
    (keyword? v) 5
    (symbol? v)  6
    :else        7))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} ^:private compare-identifiers
  ;; Namespace then name, an absent namespace sorting before any present one.
  [a b]
  (let [ns-a (namespace a)
        ns-b (namespace b)]
    (if (not= (some? ns-a) (some? ns-b))
      (if ns-a 1 -1)
      (let [ns-order (compare-utf8 (or ns-a "") (or ns-b ""))]
        (if (zero? ns-order)
          (compare-utf8 (name a) (name b))
          ns-order)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} canonical-compare
  "Order two values for canonical rendering (N4 §8.1.1). `render` breaks the
   ties the spec leaves equal — collections and other tagged values — and is
   an argument so that ordering does not depend on rendering."
  [render a b]
  (let [rank (compare (type-rank a) (type-rank b))
        primary
        (if-not (zero? rank)
          rank
          (cond
            (or (keyword? a) (symbol? a))           (compare-identifiers a b)
            (string? a)                             (compare-utf8 a b)
            (nil? a)                                0
            (or (number? a) (boolean? a) (char? a)) (compare a b)
            :else                                   (compare-utf8 (render a) (render b))))]
    (if (and (zero? primary) (not= a b))
      (compare-utf8 (render a) (render b))
      primary)))
