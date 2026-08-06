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
(ns ai.miniforge.policy-pack.canonical-edn-test
  "Tests for canonical pack serialization (N4 §8.1.1).

   Canonicalization is only useful if two runs produce identical bytes, so
   most of these build the same value two ways and compare."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.canonical-edn :as sut])
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} signable-string
  [pack]
  (String. ^bytes (sut/pack-signable-bytes pack) "UTF-8"))

(def ^{:stratum 0} ^:private array-map-threshold
  "Above this many entries Clojure switches a map literal from an array map,
   which preserves insertion order, to a hash map, which does not. Nested
   maps of this size are where unsorted serialization stops being
   reproducible."
  9)

;; ============================================================================
;; Map key ordering
;; ============================================================================
(deftest ^{:stratum 0} map-key-ordering-test
  (testing "top-level keys order by namespace then name"
    (is (= "{:a 1 :b 2 :zz/a 3 :zz/b 4}"
           (sut/canonical-edn {:zz/b 4 :b 2 :zz/a 3 :a 1}))))

  (testing "an absent namespace sorts before any present one"
    (is (= "{:zebra 1 :a/aardvark 2}"
           (sut/canonical-edn {:a/aardvark 2 :zebra 1}))))

  (testing "insertion order does not reach the output"
    (is (= (sut/canonical-edn {:a 1 :b 2 :c 3})
           (sut/canonical-edn {:c 3 :b 2 :a 1})))))

;; ============================================================================
;; Collections
;; ============================================================================
(deftest ^{:stratum 0} collection-rendering-test
  (testing "sets render as a sorted vector"
    (is (= "[:a :b :c]" (sut/canonical-edn #{:c :a :b}))))

  (testing "set iteration order does not reach the output"
    (is (= (sut/canonical-edn (into #{} [:implement :review :plan]))
           (sut/canonical-edn (into #{} [:plan :implement :review])))))

  (testing "a set above the array-set threshold still sorts"
    (let [ks (map #(keyword (str "k" %)) (range 32))]
      (is (= (sut/canonical-edn (into #{} ks))
             (sut/canonical-edn (into #{} (reverse ks)))))))

  (testing "vectors keep their declared order — sequence order is data"
    (is (= "[3 1 2]" (sut/canonical-edn [3 1 2]))))

  (testing "lists keep their declared order and their list form"
    (is (= "(3 1 2)" (sut/canonical-edn '(3 1 2)))))

  (testing "empty collections render empty"
    (is (= "{}" (sut/canonical-edn {})))
    (is (= "[]" (sut/canonical-edn [])))
    (is (= "[]" (sut/canonical-edn #{}))))

  (testing "a set of mixed types is ordered by type, not left to chance"
    (is (= (sut/canonical-edn #{1 "a" :b 'c nil true})
           (sut/canonical-edn #{'c true nil :b "a" 1})))))

;; ============================================================================
;; Scalars
;; ============================================================================
(deftest ^{:stratum 0} scalar-rendering-test
  (testing "instants render as UTC with millisecond precision"
    (is (= "#inst \"2026-01-23T00:00:00.000Z\""
           (sut/canonical-edn (Instant/parse "2026-01-23T00:00:00Z"))))
    (is (= "#inst \"2026-01-23T04:05:06.789Z\""
           (sut/canonical-edn (Instant/parse "2026-01-23T04:05:06.789Z")))))

  (testing "a Date renders identically to the Instant it denotes"
    (let [instant (Instant/parse "2026-01-23T04:05:06.789Z")]
      (is (= (sut/canonical-edn instant)
             (sut/canonical-edn (Date/from instant))))))

  (testing "sub-millisecond precision is truncated, not rounded up"
    (is (= "#inst \"2026-01-23T04:05:06.789Z\""
           (sut/canonical-edn (Instant/parse "2026-01-23T04:05:06.789999Z")))))

  (testing "strings, keywords, symbols and numbers keep their EDN form"
    (is (= "\"acl = \\\"public-read\\\"\""
           (sut/canonical-edn "acl = \"public-read\"")))
    (is (= ":rule/id" (sut/canonical-edn :rule/id)))
    (is (= "some.ns/check" (sut/canonical-edn 'some.ns/check)))
    (is (= "42" (sut/canonical-edn 42)))
    (is (= "nil" (sut/canonical-edn nil))))

  (testing "a value with no EDN reader form cannot be signed"
    ;; A live :rule/check-fn prints with an identity hash, so signing it
    ;; yields bytes that differ on every run.
    (is (thrown? clojure.lang.ExceptionInfo
                 (sut/canonical-edn {:rule/check-fn (fn [_ _] nil)})))))

;; ============================================================================
;; Whitespace
;; ============================================================================
(deftest ^{:stratum 0} whitespace-test
  (testing "one space separates key from value and entry from entry"
    (is (= "{:a 1 :b 2}" (sut/canonical-edn {:a 1 :b 2}))))

  (testing "no newlines, indentation, or trailing space"
    (let [rendered (sut/canonical-edn {:pack/rules [{:rule/id :a}
                                                    {:rule/id :b}]})]
      (is (not (re-find #"[\n\r\t]" rendered)))
      (is (not (re-find #" \}" rendered))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} recursive-map-ordering-test
  (testing "nested maps are ordered too, not only the top level"
    (is (= "{:pack/metadata {:a 1 :z 2}}"
           (sut/canonical-edn {:pack/metadata {:z 2 :a 1}}))))

  (testing "nesting above the array-map threshold serializes reproducibly"
    ;; Below the threshold a map literal keeps insertion order and an
    ;; unsorted renderer looks correct; above it, host hash order decides.
    (let [ks       (map #(keyword (str "k" %)) (range (inc array-map-threshold)))
          forward  (into {} (map-indexed (fn [i k] [k i]) ks))
          backward (into {} (reverse (map-indexed (fn [i k] [k i]) ks)))]
      (is (= (sut/canonical-edn {:pack/metadata forward})
             (sut/canonical-edn {:pack/metadata backward})))))

  (testing "maps inside vectors are ordered"
    (is (= "{:pack/rules [{:rule/id :a :rule/severity :high}]}"
           (sut/canonical-edn
            {:pack/rules [{:rule/severity :high :rule/id :a}]})))))

;; ============================================================================
;; Signable bytes
;; ============================================================================
(deftest ^{:stratum 1} pack-signable-bytes-test
  (let [pack {:pack/id "acme/security"
              :pack/version "2026.08.05"
              :pack/rules [{:rule/id :a :rule/applies-to {:phases #{:review :implement}}}]}]

    (testing "signature fields are excluded from the signed payload"
      (is (= (signable-string pack)
             (signable-string (assoc pack
                                     :pack/signature "sig"
                                     :pack/signed-by "acme-2026"
                                     :pack/signed-at (Instant/now))))))

    (testing "content changes change the payload"
      (is (not= (signable-string pack)
                (signable-string (assoc pack :pack/version "2026.08.06")))))

    (testing "the payload is UTF-8 of the canonical rendering"
      (is (= (sut/canonical-edn pack) (signable-string pack))))

    (testing "non-ASCII content encodes as UTF-8"
      (is (= (alength (.getBytes "{:pack/author \"Ω\"}" "UTF-8"))
             (alength (sut/pack-signable-bytes {:pack/author "Ω"})))))))
