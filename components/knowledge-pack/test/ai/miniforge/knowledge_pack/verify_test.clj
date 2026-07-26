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
(ns ai.miniforge.knowledge-pack.verify-test
  "Tests for `verify-pack` — the boundary that turns a stored pack
   manifest back into a trusted citation. Covers each named outcome:
     - happy path: every triple resolves, digests match
     - missing zettel
     - digest drift on a stored zettel (content rewritten)
     - lookup-fn invariant violations (id / revision-id mismatch)
     - manifest-level digest drift (pack body edited bypassing
       update-pack)"
  (:require
   [ai.miniforge.knowledge-pack.interface :as kp]
   [ai.miniforge.knowledge.interface :as knowledge]
   [clojure.test :refer [deftest is testing]])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers — build a tiny in-memory zettel store + a lookup-fn.
(defn- ^{:stratum 0} store-of
  "Build a `(zettel-id, revision-id) → zettel` map from a sequence
   of zettels. The lookup-fn closes over this map."
  [zettels]
  (into {}
        (map (fn [z] [[(:zettel/id z) (:zettel/revision-id z)] z]))
        zettels))

(defn- ^{:stratum 0} lookup-from
  "Build a lookup-fn over an in-memory store map."
  [store]
  (fn [id revision-id]
    (get store [id revision-id])))

;------------------------------------------------------------------------------ Layer 1

;; Happy path.
(deftest ^{:stratum 1} test-verify-pack-happy-path
  (testing "every triple resolves + digests match → :valid? true"
    (let [z1 (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          z2 (knowledge/create-zettel "z-2" "Z2" "Body 2." :rule)
          pack   (kp/build-pack "p" "Pack" "1.0.0" [z1 z2])
          result (kp/verify-pack pack (lookup-from (store-of [z1 z2])))]
      (is (true? (:valid? result)))
      (is (nil? (:pack/discrepancy result)))
      (is (empty? (:ref/discrepancies result))))))

;; Per-ref discrepancies.
(deftest ^{:stratum 1} test-verify-pack-missing-zettel
  (testing "a triple that doesn't resolve in the store surfaces :zettel-not-found"
    (let [z1   (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          pack (kp/build-pack "p" "Pack" "1.0.0" [z1])
          ;; Empty store — z1 isn't there.
          result (kp/verify-pack pack (lookup-from {}))]
      (is (false? (:valid? result)))
      (is (= 1 (count (:ref/discrepancies result))))
      (is (= :zettel-not-found
             (-> result :ref/discrepancies first :reason))))))

(deftest ^{:stratum 1} test-verify-pack-digest-drift
  (testing "a stored zettel's digest differing from the pack triple surfaces :digest-mismatch"
    ;; The pack references z1 with its original digest; the store
    ;; has the SAME id+revision but a tampered digest. Real-world
    ;; analog: someone edited the stored content WITHOUT going
    ;; through update-zettel, so revision-id stayed but content
    ;; drifted.
    (let [z1     (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          pack   (kp/build-pack "p" "Pack" "1.0.0" [z1])
          tampered (assoc z1 :zettel/digest
                          "0000000000000000000000000000000000000000000000000000000000000000")
          result (kp/verify-pack pack (lookup-from (store-of [tampered])))]
      (is (false? (:valid? result)))
      (is (= :digest-mismatch
             (-> result :ref/discrepancies first :reason))))))

(deftest ^{:stratum 1} test-verify-pack-lookup-fn-invariant-violation
  (testing "lookup-fn returning a zettel with mismatched :zettel/id surfaces :id-mismatch"
    (let [z1     (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          pack   (kp/build-pack "p" "Pack" "1.0.0" [z1])
          ;; Lookup-fn returns a zettel, but with a DIFFERENT :zettel/id —
          ;; should never happen in production but pin the boundary.
          bad-store {[(:zettel/id z1) (:zettel/revision-id z1)]
                     (assoc z1 :zettel/id (UUID/randomUUID))}
          result (kp/verify-pack pack (lookup-from bad-store))]
      (is (false? (:valid? result)))
      (is (= :id-mismatch
             (-> result :ref/discrepancies first :reason))))))

(deftest ^{:stratum 1} test-verify-pack-multiple-discrepancies
  (testing "verify accumulates every per-ref problem, not just the first"
    (let [z1   (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          z2   (knowledge/create-zettel "z-2" "Z2" "Body 2." :rule)
          pack (kp/build-pack "p" "Pack" "1.0.0" [z1 z2])
          ;; z1 missing; z2 digest tampered.
          tampered-z2 (assoc z2 :zettel/digest
                              "0000000000000000000000000000000000000000000000000000000000000000")
          result (kp/verify-pack pack (lookup-from (store-of [tampered-z2])))]
      (is (false? (:valid? result)))
      (is (= 2 (count (:ref/discrepancies result))))
      (is (= #{:zettel-not-found :digest-mismatch}
             (set (map :reason (:ref/discrepancies result))))))))

;; Manifest-level discrepancies.
(deftest ^{:stratum 1} test-verify-pack-detects-tampered-manifest
  (testing "a pack whose body was edited without going through update-pack surfaces :pack/digest-mismatch"
    ;; Forge a pack: build, then mutate :pack/title directly without
    ;; update-pack — :pack/digest stays at the old content's digest.
    (let [z1     (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          pack   (kp/build-pack "p" "Pack" "1.0.0" [z1])
          tampered (assoc pack :pack/title "Tampered")  ; bypasses update-pack
          result (kp/verify-pack tampered (lookup-from (store-of [z1])))]
      (is (false? (:valid? result)))
      (is (= :pack/digest-mismatch
             (-> result :pack/discrepancy :reason))))))

(deftest ^{:stratum 1} test-verify-pack-detects-missing-manifest-digest
  (testing "a legacy pack without :pack/digest fails verification with :pack/digest-missing"
    (let [legacy {:pack/id      (UUID/randomUUID)
                  :pack/uid     "legacy"
                  :pack/title   "Legacy"
                  :pack/version "0.0.1"
                  :pack/zettels []
                  :pack/created (java.util.Date.)
                  :pack/author  "user"}
          result (kp/verify-pack legacy (lookup-from {}))]
      (is (false? (:valid? result)))
      (is (= :pack/digest-missing
             (-> result :pack/discrepancy :reason))))))

(deftest ^{:stratum 1} test-verify-pack-detects-missing-revision-id
  (testing "a pack with :pack/digest but no :pack/revision-id surfaces :pack/revision-id-missing"
    ;; Partial stamping should never happen via the constructors
    ;; (build-pack / update-pack always stamp both), but a forged
    ;; pack manifest could carry digest-only — pin the boundary.
    (let [z1   (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          pack (kp/build-pack "p" "Pack" "1.0.0" [z1])
          partial (dissoc pack :pack/revision-id)
          result (kp/verify-pack partial (lookup-from (store-of [z1])))]
      (is (false? (:valid? result)))
      (is (= :pack/revision-id-missing
             (-> result :pack/discrepancy :reason))))))

(deftest ^{:stratum 1} test-verify-pack-detects-forged-revision-id
  (testing ":pack/revision-id forged independent of :pack/digest fails with :pack/revision-id-mismatch"
    ;; An attacker-rotated `:pack/revision-id` (e.g. attempting to
    ;; ride existing trust onto new content by stamping a forged
    ;; revision id) is caught because revision-id is derived from
    ;; the digest.
    (let [z1   (knowledge/create-zettel "z-1" "Z1" "Body 1." :rule)
          pack (kp/build-pack "p" "Pack" "1.0.0" [z1])
          forged-rev (UUID/randomUUID)
          tampered (assoc pack :pack/revision-id forged-rev)
          result (kp/verify-pack tampered (lookup-from (store-of [z1])))]
      (is (false? (:valid? result)))
      (is (= :pack/revision-id-mismatch
             (-> result :pack/discrepancy :reason)))
      (is (= forged-rev (-> result :pack/discrepancy :stamped-revision-id))))))
