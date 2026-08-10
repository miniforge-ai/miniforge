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
(ns ai.miniforge.content-hash.interface.instant-canonicalization-test
  "The instant boundary in canonical EDN.

   `inst?` admits BOTH `java.time.Instant` and `java.util.Date`, and
   `pr-str` renders them as unrelated things — a Date as readable
   `#inst`, an Instant as `#object[java.time.Instant 0x… \"…\"]` that
   carries the object's identity hash. So hashing the same value twice
   used to answer two different digests."
  (:require
   [clojure.edn :as edn]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.content-hash.interface :as ch])
  (:import
   [java.time Instant]
   [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ^:private now
  "A pinned instant carrying MILLISECONDS — `.789` proves losslessness."
  (Instant/parse "2026-08-01T12:34:56.789Z"))

(deftest ^{:stratum 0} unsupported-inst-type-is-refused
  ;; Hashing a value under a rendering nothing has checked is how this
  ;; defect class starts. Refuse at the boundary instead.
  (testing "an inst? that is neither Instant nor Date throws"
    (let [exotic (reify clojure.core/Inst (inst-ms* [_] 0))]
      (is (inst? exotic))
      (is (thrown-with-msg? IllegalArgumentException
                            #"Not a supported instant type"
                            (ch/content-hash {:at exotic}))))))

(deftest ^{:stratum 0} era-is-not-dropped
  ;; A `yyyy` pattern is year-of-era: it renders 1 BC and 2 AD alike as
  ;; `0002`, collapsing two distinct instants onto one digest — the same
  ;; defect this namespace exists to keep out, one layer down.
  (testing "1 BC and 2 AD are distinct instants and distinct hashes"
    (let [bc (Instant/parse "-0001-01-01T00:00:00Z")
          ad (Instant/parse "0002-01-01T00:00:00Z")]
      (is (not= bc ad))
      (is (not= (ch/canonical-edn {:at bc}) (ch/canonical-edn {:at ad})))
      (is (not= (ch/content-hash {:at bc}) (ch/content-hash {:at ad}))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} both-inst-types-are-inst?
  ;; The premise the rest of this namespace rests on. If `inst?` stopped
  ;; admitting Date, canonical EDN would not need to normalize by type —
  ;; so assert it rather than assume it.
  (testing "clojure.core/inst? admits an Instant and a Date alike"
    (is (inst? now))
    (is (inst? (Date/from now)))))

(deftest ^{:stratum 1} instant-hashing-is-deterministic
  ;; The defect. Pre-fix, `pr-str` embedded the Instant's identity hash,
  ;; so two equal values hashed differently and re-verifying a stored
  ;; digest against a recomputed one always failed.
  (testing "two equal Instants produce one digest"
    (let [a (Instant/parse "2026-08-01T12:34:56.789Z")
          b (Instant/parse "2026-08-01T12:34:56.789Z")]
      (is (= a b))
      (is (= (ch/content-hash {:at a}) (ch/content-hash {:at b})))))
  (testing "no identity hash survives into the serialization"
    (is (not (re-find #"0x" (ch/canonical-edn {:at now}))))))

(deftest ^{:stratum 1} both-inst-types-converge-on-one-rendering
  (testing "a Date and an Instant for the same moment are indistinguishable"
    (is (= (ch/canonical-edn {:at now})
           (ch/canonical-edn {:at (Date/from now)})))
    (is (= (ch/content-hash {:at now})
           (ch/content-hash {:at (Date/from now)})))))

(deftest ^{:stratum 1} date-rendering-is-unchanged
  ;; Content hashes are persisted and later re-verified, so a Date must
  ;; render to the byte-identical text `pr-str` gave it before instants
  ;; were normalized here. Asserted against Date's own print form rather
  ;; than a literal, so it tracks the JDK rather than this test.
  ;; Holds across the Gregorian range; `Date` renders pre-1582 dates on
  ;; the Julian calendar and drops the ISO `+` past year 9999, so it
  ;; disagrees with ISO-8601 outside it.
  (testing "a Date renders exactly as pr-str renders it"
    (let [d (Date/from now)]
      (is (= (str "{:at " (pr-str d) "}") (ch/canonical-edn {:at d}))))))

(deftest ^{:stratum 1} sub-millisecond-precision-survives
  ;; `Instant/now` carries microseconds on current JVMs. Truncating to
  ;; Date's millisecond width would let two distinct instants collide
  ;; inside a tamper-evidence hash.
  (testing "microseconds are neither dropped nor collapsed"
    (let [micros (Instant/parse "2026-08-01T12:34:56.789123Z")]
      (is (= "{:at #inst \"2026-08-01T12:34:56.789123-00:00\"}"
             (ch/canonical-edn {:at micros})))
      (is (not= (ch/content-hash {:at micros}) (ch/content-hash {:at now}))))))

(deftest ^{:stratum 1} normalized-instants-are-readable-edn
  ;; `#object[...]` has no reader, so pre-fix an Instant made the whole
  ;; canonical string unreadable. The reader answers a Date for `#inst`,
  ;; so the round trip recovers the instant, not the original type.
  (testing "canonical EDN holding an instant reads back to the same moment"
    (doseq [[label t] [["Instant" now] ["Date" (Date/from now)]]]
      (testing label
        (is (= (Date/from now) (:at (edn/read-string (ch/canonical-edn {:at t})))))))))

(deftest ^{:stratum 1} instants-normalize-at-any-depth
  (testing "nested, in sequences, in sets, and as a map key"
    (is (= (ch/canonical-edn {:a {:b [now]}})
           (ch/canonical-edn {:a {:b [(Date/from now)]}})))
    (is (= (ch/canonical-edn #{now}) (ch/canonical-edn #{(Date/from now)})))
    (is (= (ch/canonical-edn {now :v}) (ch/canonical-edn {(Date/from now) :v})))))
