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
(ns ai.miniforge.knowledge-pack.pack-test
  "Tests for `knowledge-pack/build-pack` + `update-pack` + projection
   helpers. Covers the same revision-keyed identity invariants the
   zettel-side tests pin (deterministic over content; rotates on
   content change; stable on operational-metadata change; spoof
   guard on derived fields; legacy backfill)."
  (:require
   [ai.miniforge.knowledge-pack.interface :as kp]
   [ai.miniforge.knowledge-pack.schema :as kp-schema]
   [ai.miniforge.knowledge.interface :as knowledge]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0

;; Helpers.
(defn- ^{:stratum 0} z
  "Build a fresh stamped zettel via the OSS knowledge constructor.
   Each call produces a different :zettel/id but the same
   :zettel/revision-id when the content is identical."
  [& {:keys [uid title content]
      :or {uid     "test-z"
           title   "Test Zettel"
           content "# Body of the test zettel."}}]
  (knowledge/create-zettel uid title content :rule))

(defn- ^{:stratum 0} pack-of
  "Build a pack with sensible defaults around the supplied zettels."
  [zettels & overrides]
  (apply kp/build-pack
         "test-pack" "Test Pack" "1.0.0" zettels
         overrides))

(deftest ^{:stratum 0} test-update-backfills-derived-fields-on-legacy-pack
  (testing "a legacy pack without derived fields gets them stamped on first update"
    (let [legacy {:pack/id      (UUID/randomUUID)
                  :pack/uid     "legacy-pack"
                  :pack/title   "Legacy"
                  :pack/version "0.0.1"
                  :pack/zettels []
                  :pack/created (java.util.Date.)
                  :pack/author  "user"}
          updated (kp/update-pack legacy {:fleet/oss-version "1.0.0"})]
      (is (string? (:pack/digest updated)))
      (is (= 64 (count (:pack/digest updated))))
      (is (instance? UUID (:pack/revision-id updated))))))

(deftest ^{:stratum 0} test-zettel-ref-throws-on-missing-fields
  (testing "a zettel missing revision-id / digest cannot be projected to a pack triple"
    ;; The constructor refuses to silently emit a malformed triple —
    ;; per Decision 6, packs reference content-addressable zettels
    ;; only.
    (let [legacy {:zettel/id (UUID/randomUUID)
                  :zettel/uid "legacy"
                  :zettel/title "Legacy"
                  :zettel/content "Body."
                  :zettel/type :rule
                  :zettel/created (java.util.Date.)
                  :zettel/author "user"}]
      (is (thrown? clojure.lang.ExceptionInfo (kp/zettel->ref legacy))))))

(deftest ^{:stratum 0} test-zettelref-schema-rejects-uppercase-digest
  (testing ":zettel/digest must be lowercase hex per the shared regex"
    (let [bad {:zettel/id          (UUID/randomUUID)
               :zettel/revision-id (UUID/randomUUID)
               :zettel/digest      "DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF"}]
      (is (false? (m/validate kp-schema/ZettelRef bad))))))

;------------------------------------------------------------------------------ Layer 1

;; build-pack stamps revision + digest deterministically.
(deftest ^{:stratum 1} test-build-pack-stamps-revision-and-digest
  (testing "every fresh pack carries a 64-char hex digest + a UUID revision-id"
    (let [p (pack-of [(z) (z :uid "second" :content "different content")])]
      (is (string? (:pack/digest p)))
      (is (re-matches #"^[0-9a-f]{64}$" (:pack/digest p)))
      (is (instance? UUID (:pack/revision-id p))))))

(deftest ^{:stratum 1} test-pack-revision-id-is-deterministic-over-content
  (testing "two packs that reference the same zettel triples land on the same revision-id and digest"
    ;; The pack's content-projection includes :pack/zettels (the
    ;; vector of (zettel-id, revision-id, digest) triples), so
    ;; pack determinism is against the SAME REFERENCED ZETTELS,
    ;; not against \"two zettels that happen to have identical
    ;; content\". `create-zettel` mints a fresh `:zettel/id` per
    ;; call, so two zettels with identical content have different
    ;; ids and would change the pack triple. The fixture binds
    ;; `shared` once so both packs reference the same zettel id.
    (let [shared (z)
          p1 (pack-of [shared])
          p2 (pack-of [shared])]
      (is (not= (:pack/id p1) (:pack/id p2))
          "logical :pack/id is fresh per call")
      (is (= (:pack/digest p1)      (:pack/digest p2)))
      (is (= (:pack/revision-id p1) (:pack/revision-id p2))))))

(deftest ^{:stratum 1} test-distinct-content-produces-distinct-revision
  (testing "any change to a content-bearing field produces a different revision-id"
    (let [shared (z)
          p1 (pack-of [shared])
          p2 (pack-of [shared] :description "extra desc")]
      (is (not= (:pack/digest p1)      (:pack/digest p2)))
      (is (not= (:pack/revision-id p1) (:pack/revision-id p2))))))

;; update-pack semantics.
(deftest ^{:stratum 1} test-update-content-rotates-revision
  (testing "changing :pack/title rotates the revision"
    (let [p1 (pack-of [(z)])
          p2 (kp/update-pack p1 {:pack/title "Renamed"})]
      (is (not= (:pack/revision-id p1) (:pack/revision-id p2))))))

(deftest ^{:stratum 1} test-update-zettels-rotates-revision
  (testing "adding a zettel ref rotates the revision"
    (let [p1 (pack-of [(z)])
          p2 (kp/add-zettel p1 (z :uid "second" :content "different content"))]
      (is (not= (:pack/revision-id p1) (:pack/revision-id p2)))
      (is (= 1 (count (:pack/zettels p1))))
      (is (= 2 (count (:pack/zettels p2)))))))

(deftest ^{:stratum 1} test-remove-zettel-rotates-revision
  (testing "removing a zettel ref rotates the revision"
    (let [zettel (z)
          p1 (pack-of [zettel])
          p2 (kp/remove-zettel p1 (:zettel/id zettel))]
      (is (not= (:pack/revision-id p1) (:pack/revision-id p2)))
      (is (empty? (:pack/zettels p2))))))

(deftest ^{:stratum 1} test-update-operational-metadata-leaves-revision-intact
  (testing "operational-only changes (privacy, share scope, oss-version) preserve revision"
    (let [p1 (pack-of [(z)] :privacy/classification :internal :fleet/oss-version "1.0.0")
          p2 (kp/update-pack p1 {:privacy/classification :restricted})
          p3 (kp/update-pack p2 {:fleet/share-scope :team})
          p4 (kp/update-pack p3 {:fleet/oss-version "1.0.1"})]
      (is (= (:pack/revision-id p1) (:pack/revision-id p2)))
      (is (= (:pack/revision-id p2) (:pack/revision-id p3)))
      (is (= (:pack/revision-id p3) (:pack/revision-id p4)))
      (is (= (:pack/digest p1) (:pack/digest p4))))))

(deftest ^{:stratum 1} test-update-ignores-caller-supplied-derived-fields
  (testing "callers cannot spoof :pack/digest or :pack/revision-id via update-pack"
    (let [p1 (pack-of [(z)])
          forged-digest "0000000000000000000000000000000000000000000000000000000000000000"
          forged-rev    (UUID/randomUUID)
          p2 (kp/update-pack p1 {:pack/digest      forged-digest
                                 :pack/revision-id forged-rev})]
      (is (not= forged-digest (:pack/digest p2)))
      (is (not= forged-rev (:pack/revision-id p2)))
      (is (= (:pack/digest p1) (:pack/digest p2))
          "digest stays equal to p1's because content didn't change"))))

;; zettel->ref projection.
(deftest ^{:stratum 1} test-zettel-ref-projects-canonical-triple
  (testing "zettel->ref returns a closed (id, revision-id, digest) map"
    (let [zettel (z)
          ref    (kp/zettel->ref zettel)]
      (is (= (set [:zettel/id :zettel/revision-id :zettel/digest])
             (set (keys ref))))
      (is (= (:zettel/id zettel)          (:zettel/id ref)))
      (is (= (:zettel/revision-id zettel) (:zettel/revision-id ref)))
      (is (= (:zettel/digest zettel)      (:zettel/digest ref))))))

;; Schema acceptance.
(deftest ^{:stratum 1} test-built-pack-validates-against-schema
  (testing "the constructor stamps a pack that round-trips through Malli"
    (let [p (pack-of [(z)] :description "A test pack."
                     :fleet/shareable true
                     :fleet/share-scope :org
                     :privacy/classification :internal
                     :fleet/oss-version "1.0.0")]
      (is (m/validate kp/KnowledgePack p)))))
