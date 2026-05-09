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

(ns ai.miniforge.event-stream.snowflake-test
  "Tests for the BD-2b Snowflake event-id generator."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [ai.miniforge.event-stream.snowflake :as sf])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Helpers

(defn- with-temp-workers-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "snowflake-test-workers-"
             (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (f (.toFile dir))
      (finally
        (doseq [file (reverse (file-seq (.toFile dir)))]
          (.delete ^java.io.File file))))))

(defn- fixed-clock
  "Return a 0-arg fn that returns successive values from `times`. Once
   exhausted, repeats the last value (so the generator can spin without
   running off the end)."
  [times]
  (let [counter (atom 0)
        ts (vec times)]
    (fn []
      (let [i @counter
            v (nth ts (min i (dec (count ts))))]
        (swap! counter inc)
        v))))

;; ─── Test fixture constants ─────────────────────────────────────────
;; The generator state initialises `last-ts` and `last-seq` to a sentinel
;; meaning "no id has been emitted yet". `next-state` interprets this as
;; "any wall ms ≥ epoch advances state and resets seq to 0", so the
;; first emission goes through the third (forward-time) branch. The
;; sentinel must be < any valid (ms, seq) pair the generator could
;; observe; -1 is the conventional Java/Clojure sentinel for unset.
(def ^:private ^:const ^long unset-timestamp-sentinel -1)
(def ^:private ^:const ^long unset-sequence-sentinel  -1)

;; Bypass the FileLock-based worker-id lease in unit tests by pinning a
;; deterministic worker id and providing a fake lease handle. `0` is
;; chosen for readability; any 0..max-workers-1 value works.
(def ^:private ^:const ^long test-worker-id 0)

(def ^:private fresh-test-state
  "A fresh-but-quiesced generator state — what the production
   `initial-state` factory would produce for the test worker-id, but
   defined locally so tests don't reach into a private fn."
  {:last-ts   unset-timestamp-sentinel
   :last-seq  unset-sequence-sentinel
   :worker-id test-worker-id})

(def ^:private fake-test-lease
  "Sentinel lease handle for tests that bypass the file-lock path."
  {:worker-id test-worker-id})

(defn- generator-with-fixed-clock [times]
  {:state     (atom fresh-test-state)
   :worker-id test-worker-id
   :lease     fake-test-lease
   :now-fn    (fixed-clock times)})

;------------------------------------------------------------------------------ Pure id-composition

(deftest compose-id-packs-bits-correctly
  ;; Verify the bit layout: 41-bit ts << 22 | 10-bit worker << 12 | 12-bit seq.
  (let [id (sf/compose-id 0 0 0)]
    (is (zero? id)))
  (let [id (sf/compose-id 1 0 0)]
    (is (= (bit-shift-left 1 22) id)))
  (let [id (sf/compose-id 0 1 0)]
    (is (= (bit-shift-left 1 12) id)))
  (let [id (sf/compose-id 0 0 1)]
    (is (= 1 id)))
  (let [id (sf/compose-id 0xff 0x3ff 0xfff)]
    (is (= (bit-or (bit-shift-left 0xff 22)
                   (bit-shift-left 0x3ff 12)
                   0xfff)
           id))))

(deftest format-hex-produces-16-char-lowercase
  (is (= "0000000000000000" (sf/format-hex 0)))
  (is (= "0000000000000001" (sf/format-hex 1)))
  (is (= "ffffffffffffffff" (sf/format-hex -1)))) ; all-ones long

(deftest long->uuid-zeros-low-bits
  (let [u (sf/long->uuid 0x018f3a9c8e4b12d0)]
    (is (instance? UUID u))
    (is (= 0x018f3a9c8e4b12d0 (.getMostSignificantBits ^UUID u)))
    (is (zero? (.getLeastSignificantBits ^UUID u))))
  ;; Zero round-trips.
  (is (= "00000000-0000-0000-0000-000000000000"
         (str (sf/long->uuid 0)))))

(deftest uuid->hex-extracts-msb
  (let [u (sf/long->uuid 0x018f3a9c8e4b12d0)]
    (is (= "018f3a9c8e4b12d0" (sf/uuid->hex u)))))

;------------------------------------------------------------------------------ next-id! semantics

(deftest next-id!-monotonic-within-same-ms
  (let [base-ts sf/miniforge-epoch-ms
        gen (generator-with-fixed-clock (repeat 10 base-ts))
        ids (vec (repeatedly 5 #(sf/next-id-long! gen)))]
    (is (= 5 (count (set ids))) "ids are unique")
    (is (= ids (sort ids)) "ids are monotonic in same-ms (sequence increments)")
    (let [seqs (map #(bit-and % sf/max-seq) ids)]
      (is (= [0 1 2 3 4] seqs)
          "sequence increments deterministically inside the same millisecond"))))

(deftest next-id!-resets-sequence-on-ms-advance
  (let [base-ts sf/miniforge-epoch-ms
        ;; First call at base-ts, second call at base-ts + 1ms.
        gen (generator-with-fixed-clock [base-ts (inc base-ts)])
        id1 (sf/next-id-long! gen)
        id2 (sf/next-id-long! gen)]
    (is (zero? (bit-and id1 sf/max-seq)) "first id at new ms has seq=0")
    (is (zero? (bit-and id2 sf/max-seq)) "second id at next ms also resets to seq=0")))

(deftest next-id!-advances-ms-on-sequence-exhaustion
  ;; Force seq to 4095 manually, then call next-id! at the same ms.
  ;; The generator must spin — but with a fixed-clock that returns
  ;; the same ms, it would spin forever. We simulate the wall clock
  ;; advancing on the second call.
  (let [base-ts sf/miniforge-epoch-ms
        gen (generator-with-fixed-clock (concat (repeat 10 base-ts)
                                                (repeat 10 (inc base-ts))))]
    ;; Pre-seed state so seq is at max.
    (reset! (:state gen) {:last-ts   base-ts
                          :last-seq  sf/max-seq
                          :worker-id test-worker-id})
    (let [id (sf/next-id-long! gen)]
      ;; The id should be at base-ts + 1, seq = 0.
      (is (= (- (inc base-ts) sf/miniforge-epoch-ms)
             (bit-shift-right id sf/ts-shift))
          "exhaustion at base-ts forces advance to base-ts+1")
      (is (zero? (bit-and id sf/max-seq))
          "sequence resets at the new ms"))))

(deftest next-id!-throws-on-pre-epoch-clock
  ;; If the wall clock is somehow before the miniforge epoch, the bit
  ;; layout would corrupt (negative ts shifts into worker bits). Throw
  ;; loudly rather than silently emit garbage.
  (let [gen (generator-with-fixed-clock [(- sf/miniforge-epoch-ms 1)])]
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"before the miniforge epoch"
                          (sf/next-id-long! gen)))))

(deftest next-id!-stalls-on-clock-rollback
  (let [base-ts (+ sf/miniforge-epoch-ms 1000)
        ;; Call 1: time goes BACKWARD by 100ms. Generator must stall.
        ;; Once enough retries land back at base-ts, it succeeds.
        gen (generator-with-fixed-clock
             (concat [base-ts]                       ; first emission sets last-ts
                     (repeat 5 (- base-ts 100))      ; rollback - generator stalls
                     (repeat 10 (inc base-ts))))]    ; clock recovers
    ;; First call establishes last-ts.
    (sf/next-id-long! gen)
    ;; Second call sees rollback, retries; eventually succeeds at inc base-ts.
    (let [id (sf/next-id-long! gen)
          ts-component (- (bit-shift-right id sf/ts-shift) 0)
          expected-ts (- (inc base-ts) sf/miniforge-epoch-ms)]
      (is (= expected-ts ts-component)
          "rollback resolves once wall clock surpasses last-ts"))))

(deftest next-id!-returns-uuid-with-zero-low-bits
  (let [base-ts sf/miniforge-epoch-ms
        gen (generator-with-fixed-clock (repeat 5 base-ts))
        u (sf/next-id! gen)]
    (is (instance? UUID u))
    (is (zero? (.getLeastSignificantBits ^UUID u))
        "snowflake UUIDs always have zero low 64 bits — that's the discriminator")))

(deftest next-id-hex!-is-16-lowercase-hex
  (let [base-ts sf/miniforge-epoch-ms
        gen (generator-with-fixed-clock (repeat 5 base-ts))
        h (sf/next-id-hex! gen)]
    (is (= 16 (count h)))
    (is (re-matches #"[0-9a-f]{16}" h))))

(deftest next-id-hex!-sorts-by-creation-order
  ;; Three ids generated 100ms apart should sort lex.
  (let [base-ts sf/miniforge-epoch-ms
        gen (generator-with-fixed-clock [base-ts
                                         (+ base-ts 100)
                                         (+ base-ts 200)])
        a (sf/next-id-hex! gen)
        b (sf/next-id-hex! gen)
        c (sf/next-id-hex! gen)]
    (is (< (compare a b) 0))
    (is (< (compare b c) 0))))

;------------------------------------------------------------------------------ worker-id leasing

(deftest acquire-worker-lease!-takes-id-zero-when-empty
  (with-temp-workers-dir
    (fn [dir]
      (let [lease (sf/acquire-worker-lease! dir)]
        (try
          (is (= 0 (:worker-id lease)))
          (is (.exists (io/file dir "0.lease")))
          (finally (sf/release-lease! lease)))))))

(deftest acquire-worker-lease!-skips-locked-slot
  (with-temp-workers-dir
    (fn [dir]
      (let [first-lease (sf/acquire-worker-lease! dir)]
        (try
          (let [second-lease (sf/acquire-worker-lease! dir)]
            (try
              (is (= 0 (:worker-id first-lease)))
              (is (= 1 (:worker-id second-lease))
                  "a second concurrent acquire takes the next free slot")
              (finally (sf/release-lease! second-lease))))
          (finally (sf/release-lease! first-lease)))))))

(deftest acquire-worker-lease!-reclaims-released-slot
  (with-temp-workers-dir
    (fn [dir]
      (let [lease-a (sf/acquire-worker-lease! dir)]
        (sf/release-lease! lease-a))
      (let [lease-b (sf/acquire-worker-lease! dir)]
        (try
          (is (= 0 (:worker-id lease-b))
              "after release, slot 0 becomes acquirable again")
          (finally (sf/release-lease! lease-b)))))))

(deftest release-lease!-is-idempotent
  (with-temp-workers-dir
    (fn [dir]
      (let [lease (sf/acquire-worker-lease! dir)]
        (sf/release-lease! lease)
        (is (nil? (sf/release-lease! lease))
            "second release is a no-op, not an error")))))

(deftest create-generator-acquires-worker-lease
  (with-temp-workers-dir
    (fn [dir]
      (let [gen (sf/create-generator {:workers-dir dir})]
        (try
          (is (some? (:lease gen)))
          (is (= 0 (:worker-id gen)))
          (let [u (sf/next-id! gen)]
            (is (instance? UUID u)))
          (finally (sf/close! gen)))))))
