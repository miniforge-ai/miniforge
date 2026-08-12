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
(ns ai.miniforge.buzzword-bingo.card
  "Deal a five-by-five bingo card of terms from a catalog.

   The same seed always deals the same card, on any host, so a card can
   be rebuilt from its seed rather than stored, and two people watching
   one session see the same board.")

;------------------------------------------------------------------------------ Layer 0

;; Geometry and deterministic ordering
(def ^{:stratum 0} square-count
  "Squares on a card, the middle one free."
  25)

(def ^{:stratum 0} free-square-index
  "Position of the free square, at the centre of the card."
  12)

;; The modulus keeps the running hash small enough that every intermediate
;; product is exact in both a JVM long and a JavaScript double, so the two
;; hosts agree on the ordering and therefore deal the same card.
(defn- ^{:stratum 0} string-hash [s]
  (let [seed       7
        multiplier 31
        modulus    1000003]
    (loop [i 0
           h seed]
      (if (= i (count s))
        h
        (recur (inc i)
               (mod (+ (* multiplier h)
                       #?(:clj (int (.charAt ^String s i)) :cljs (.charCodeAt s i)))
                    modulus))))))

(defn- ^{:stratum 0} square-of [index entry]
  {:square/index    index
   :square/id       (:entry/id entry)
   :square/term     (:entry/display entry)
   :square/category (:entry/category entry)})

(defn ^{:stratum 0} ids-on
  "The set of term ids `card` can be marked with."
  [card]
  (into #{} (keep :square/id) (:card/squares card)))

;------------------------------------------------------------------------------ Layer 1

;; Hashing seed and id together is what makes a different seed deal a different
;; card; the id also breaks ties, so the order stays total when two terms hash
;; alike.
;; The whole keyword, not its name: `name` discards the namespace, so
;; :a/robust and :b/robust would hash and sort alike and the tie-break would
;; stop being total. The compiler never mints a namespaced id, but card-for
;; accepts any catalog a caller hands it.
(defn- ^{:stratum 1} draw-order [seed entry]
  (let [id (str (:entry/id entry))]
    [(string-hash (str seed "|" id)) id]))

(def ^{:stratum 1} ^:private term-square-indices
  (vec (remove #(= free-square-index %) (range square-count))))

(def ^{:stratum 1} ^:private free-square
  {:square/index free-square-index
   :square/free? true})

;------------------------------------------------------------------------------ Layer 2

;; Cards
(defn ^{:stratum 2} card-for
  "Deal the card for `seed` from `entries`.

   Returns `{:card/seed s :card/squares [...]}` with the centre square
   free. Deterministic in both arguments."
  [seed entries]
  (when (< (count entries) (count term-square-indices))
    (throw (#?(:clj IllegalArgumentException. :cljs js/Error.)
            (str "Need at least " (count term-square-indices)
                 " entries to fill a card, got " (count entries)))))
  (let [drawn   (take (count term-square-indices) (sort-by (partial draw-order seed) entries))
        squares (conj (map square-of term-square-indices drawn) free-square)]
    {:card/seed    seed
     :card/squares (vec (sort-by :square/index squares))}))

(comment
  (card-for "session-1" [{:entry/id :robust :entry/display "robust"
                          :entry/category :marketing}]))
