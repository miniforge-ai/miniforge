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

(ns ai.miniforge.knowledge.zettel-revision-test
  "Tests for the revision-keyed identity + Fleet-share schema additions
   landed for miniforge-fleet's Phase E (Decisions 6 + 8 + 13).

   Three areas:
     1. `:zettel/revision-id` + `:zettel/digest` are stamped at creation,
        deterministically derived from content (same content → same
        revision-id), and rotate when a content-bearing field changes
        but NOT when only operational metadata changes.
     2. The new schema fields (`:fleet/shareable`, `:fleet/share-scope`,
        `:privacy/classification`, `:fleet/oss-version`) accept their
        documented values and reject malformed ones.
     3. Round-trip: a zettel with the new fields validates."
  (:require
   [ai.miniforge.knowledge.schema :as schema]
   [ai.miniforge.knowledge.zettel :as zettel]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers.

(defn- new-z
  "A zettel built via the public constructor, with optional kwargs."
  [& kvs]
  (apply zettel/create-zettel
         "test-uid" "Test Title" "# Body content for the test\n\nMore body."
         :rule
         kvs))

;------------------------------------------------------------------------------ Layer 1
;; create-zettel stamps revision-id + digest.

(deftest test-create-zettel-stamps-revision-and-digest
  (testing "every fresh zettel carries a 64-char hex digest + a UUID revision-id"
    (let [z (new-z)]
      (is (string? (:zettel/digest z)))
      (is (= 64 (count (:zettel/digest z))) "SHA-256 hex = 64 chars")
      (is (instance? java.util.UUID (:zettel/revision-id z))))))

(deftest test-revision-id-is-deterministic-over-content
  (testing "two zettels with identical content land on the same revision-id and digest"
    ;; The :zettel/id (logical identity) differs because the constructor mints
    ;; a fresh random UUID; the revision-id is derived from content and so
    ;; matches across both. This is the property miniforge-fleet's E.1
    ;; idempotent-retry path needs.
    (let [z1 (new-z)
          z2 (new-z)]
      (is (not= (:zettel/id z1) (:zettel/id z2))
          "logical :zettel/id is fresh per call")
      (is (= (:zettel/digest z1)      (:zettel/digest z2)))
      (is (= (:zettel/revision-id z1) (:zettel/revision-id z2))))))

(deftest test-distinct-content-produces-distinct-revision
  (testing "any change to a content-bearing field produces a different revision-id"
    (let [z1 (new-z :tags [:a])
          z2 (new-z :tags [:b])]
      (is (not= (:zettel/digest z1) (:zettel/digest z2)))
      (is (not= (:zettel/revision-id z1) (:zettel/revision-id z2))))))

;------------------------------------------------------------------------------ Layer 2
;; update-zettel rotates revision on content change, leaves it alone otherwise.

(deftest test-update-content-field-rotates-revision
  (testing "changing :zettel/content rotates :zettel/revision-id + :zettel/digest"
    (let [z1 (new-z)
          z2 (zettel/update-zettel z1 {:zettel/content "# Brand new content"})]
      (is (not= (:zettel/digest z1)      (:zettel/digest z2)))
      (is (not= (:zettel/revision-id z1) (:zettel/revision-id z2))))))

(deftest test-update-tags-rotates-revision
  (testing "changing :zettel/tags rotates the revision (tags are content-bearing)"
    (let [z1 (new-z :tags [:a])
          z2 (zettel/update-zettel z1 {:zettel/tags [:a :b]})]
      (is (not= (:zettel/revision-id z1) (:zettel/revision-id z2))))))

(deftest test-update-operational-metadata-leaves-revision-intact
  (testing "operational-only changes (privacy classification, share scope, oss-version) preserve revision-id"
    ;; Decision 6: trust attaches to the immutable revision. Changing a
    ;; share-policy must NOT silently migrate trust onto a new revision —
    ;; the revision is the same content so the revision-id stays put.
    (let [z1 (new-z :privacy/classification :internal :fleet/oss-version "1.0.0")
          z2 (zettel/update-zettel z1 {:privacy/classification :restricted})
          z3 (zettel/update-zettel z2 {:fleet/share-scope :team})
          z4 (zettel/update-zettel z3 {:fleet/oss-version "1.0.1"})]
      (is (= (:zettel/revision-id z1) (:zettel/revision-id z2)))
      (is (= (:zettel/revision-id z2) (:zettel/revision-id z3)))
      (is (= (:zettel/revision-id z3) (:zettel/revision-id z4)))
      (is (= (:zettel/digest z1) (:zettel/digest z4))
          "digest unchanged across operational-metadata updates"))))

(deftest test-update-ignores-caller-supplied-derived-fields
  (testing "callers cannot spoof :zettel/digest or :zettel/revision-id via update-zettel"
    ;; A producer trying to stamp a forged digest/revision-id (e.g. to
    ;; ride existing trust onto new content) gets neither — the updater
    ;; drops both fields from `changes` and re-stamps from the
    ;; content projection.
    (let [z1     (new-z)
          forged-digest "0000000000000000000000000000000000000000000000000000000000000000"
          forged-rev    (java.util.UUID/randomUUID)
          z2     (zettel/update-zettel z1 {:zettel/digest      forged-digest
                                           :zettel/revision-id forged-rev})]
      (is (not= forged-digest (:zettel/digest z2))
          "forged :zettel/digest dropped, recomputed from content")
      (is (not= forged-rev (:zettel/revision-id z2))
          "forged :zettel/revision-id dropped, recomputed from content")
      (is (= (:zettel/digest z1) (:zettel/digest z2))
          "digest stays equal to z1's because content didn't change")
      (is (= (:zettel/revision-id z1) (:zettel/revision-id z2)))))

  (testing "spoof attempt combined with a real content change still rotates correctly"
    (let [z1 (new-z)
          z2 (zettel/update-zettel z1 {:zettel/content      "# Updated content"
                                       :zettel/digest       "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                                       :zettel/revision-id  (java.util.UUID/randomUUID)})]
      (is (not= (:zettel/digest z1) (:zettel/digest z2))
          "content change rotated digest")
      (is (not= "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
                (:zettel/digest z2))
          "rotated digest comes from content, not from the spoof attempt"))))

(deftest test-update-backfills-derived-fields-on-legacy-zettel
  (testing "a legacy zettel without :zettel/digest / :zettel/revision-id gets them stamped on first update"
    ;; Decision 6 wants the system to converge on a fully-stamped state
    ;; without an explicit migration. update-zettel handles that
    ;; convergence implicitly by always re-stamping after merge.
    (let [legacy {:zettel/id      (java.util.UUID/randomUUID)
                  :zettel/uid     "legacy-z"
                  :zettel/title   "Legacy Zettel"
                  :zettel/content "Body."
                  :zettel/type    :rule
                  :zettel/created (java.util.Date.)
                  :zettel/author  "user"}
          updated (zettel/update-zettel legacy {:fleet/oss-version "1.0.0"})]
      (is (string? (:zettel/digest updated))
          ":zettel/digest backfilled by update")
      (is (= 64 (count (:zettel/digest updated))))
      (is (instance? java.util.UUID (:zettel/revision-id updated))
          ":zettel/revision-id backfilled by update")
      (is (= "1.0.0" (:fleet/oss-version updated))
          "the operational change still landed"))))

;------------------------------------------------------------------------------ Layer 2.5
;; Schema digest validation — accepts canonical lowercase hex; rejects
;; uppercase / non-hex / wrong-length.

(deftest test-digest-schema-accepts-canonical-hex
  (testing "the digest the constructor stamps is lowercase SHA-256 hex and validates"
    (let [z (new-z)]
      (is (m/validate schema/Zettel z))
      (is (re-matches #"^[0-9a-f]{64}$" (:zettel/digest z))))))

(deftest test-digest-schema-rejects-uppercase-hex
  (testing "uppercase hex fails — content-hash always returns lowercase, so uppercase = forged or corrupted"
    (let [z (assoc (new-z) :zettel/digest
                   "DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF")]
      (is (false? (m/validate schema/Zettel z))))))

(deftest test-digest-schema-rejects-non-hex
  (testing "64-char string with non-hex characters fails"
    (let [z (assoc (new-z) :zettel/digest
                   (apply str (repeat 64 \z)))]
      (is (false? (m/validate schema/Zettel z))))))

(deftest test-digest-schema-rejects-wrong-length
  (testing "wrong-length hex fails"
    (let [too-short (apply str (repeat 63 \a))
          too-long  (apply str (repeat 65 \a))]
      (is (false? (m/validate schema/Zettel (assoc (new-z) :zettel/digest too-short))))
      (is (false? (m/validate schema/Zettel (assoc (new-z) :zettel/digest too-long)))))))

;------------------------------------------------------------------------------ Layer 3
;; Fleet-share schema fields accept their documented values.

(deftest test-shareable-field-accepts-boolean
  (testing ":fleet/shareable true / false both validate; the constructor stamps it"
    (doseq [b [true false]]
      (let [z (new-z :fleet/shareable b)]
        (is (= b (:fleet/shareable z)))
        (is (m/validate schema/Zettel z))))))

(deftest test-share-scope-accepts-enum
  (testing ":fleet/share-scope accepts the closed enum :org / :team / :repo / :workflow"
    (doseq [s [:org :team :repo :workflow]]
      (let [z (new-z :fleet/share-scope s)]
        (is (= s (:fleet/share-scope z)))
        (is (m/validate schema/Zettel z))))))

(deftest test-share-scope-rejects-bad-value
  (testing ":fleet/share-scope outside the enum fails schema validation"
    (let [z (assoc (new-z) :fleet/share-scope :galaxy)]
      (is (false? (m/validate schema/Zettel z))))))

(deftest test-classification-accepts-enum
  (testing ":privacy/classification accepts :public-org / :internal / :restricted / :secret"
    (doseq [c [:public-org :internal :restricted :secret]]
      (let [z (new-z :privacy/classification c)]
        (is (= c (:privacy/classification z)))
        (is (m/validate schema/Zettel z))))))

(deftest test-classification-rejects-bad-value
  (testing ":privacy/classification outside the enum fails validation"
    (let [z (assoc (new-z) :privacy/classification :other)]
      (is (false? (m/validate schema/Zettel z))))))

(deftest test-oss-version-accepts-string
  (testing ":fleet/oss-version accepts a non-empty string"
    (let [z (new-z :fleet/oss-version "2026.05.07.42")]
      (is (= "2026.05.07.42" (:fleet/oss-version z)))
      (is (m/validate schema/Zettel z)))))

(deftest test-oss-version-rejects-empty-string
  (testing ":fleet/oss-version cannot be the empty string (would defeat the provenance)"
    (let [z (assoc (new-z) :fleet/oss-version "")]
      (is (false? (m/validate schema/Zettel z))))))

;------------------------------------------------------------------------------ Layer 4
;; Schema regression — pre-Decision-6 zettels still validate.

(deftest test-legacy-zettel-without-fleet-fields-still-validates
  (testing "a zettel built without any of the new fields still passes the schema"
    ;; Round-trip a manually-shaped zettel (no Fleet fields, no
    ;; revision-id) so legacy file-backed stores keep working until
    ;; a future migration backfills the new fields.
    (let [legacy {:zettel/id      (random-uuid)
                  :zettel/uid     "legacy-1"
                  :zettel/title   "Legacy Zettel"
                  :zettel/content "Body."
                  :zettel/type    :rule
                  :zettel/created (java.util.Date.)
                  :zettel/author  "user"}]
      (is (m/validate schema/Zettel legacy)))))

;------------------------------------------------------------------------------ Layer 5
;; :zettel/trust-level — per-revision trust (Decision 6 + 8; closes #836).

(deftest test-create-zettel-defaults-trust-level-untrusted
  (testing "every fresh zettel starts with :zettel/trust-level :untrusted"
    ;; Bypassing create-zettel doesn't grant trust — only
    ;; learning/promote-learning stamps :trusted, and it does so via
    ;; store/put-zettel (not update-zettel).
    (let [z (new-z)]
      (is (= :untrusted (:zettel/trust-level z))))))

(deftest test-trust-level-resets-on-content-rotation
  (testing "editing :zettel/content rotates revision-id AND resets trust to :untrusted"
    ;; The gap #836 closes: a producer who has already promoted a
    ;; zettel to :trusted and then edits its content must NOT
    ;; carry trust onto the new revision. update-zettel detects
    ;; the rotation (new revision-id != old) and forces
    ;; :untrusted on the result.
    (let [z1     (-> (new-z) (assoc :zettel/trust-level :trusted))
          z2     (zettel/update-zettel z1 {:zettel/content "# Brand new content"})]
      (is (not= (:zettel/revision-id z1) (:zettel/revision-id z2))
          "revision rotated")
      (is (= :untrusted (:zettel/trust-level z2))
          "trust reset on content rotation"))))

(deftest test-trust-level-resets-on-tag-rotation
  (testing "any content-bearing rotation resets trust — not just :zettel/content"
    (let [z1 (-> (new-z :tags [:a]) (assoc :zettel/trust-level :trusted))
          z2 (zettel/update-zettel z1 {:zettel/tags [:a :b]})]
      (is (not= (:zettel/revision-id z1) (:zettel/revision-id z2)))
      (is (= :untrusted (:zettel/trust-level z2))))))

(deftest test-trust-level-preserved-on-operational-only-update
  (testing "operational-metadata-only updates preserve trust (no revision rotation)"
    ;; Decision 6: changing operational policy must NOT silently
    ;; downgrade trust either. The current revision is still the
    ;; one that was reviewed.
    (let [z1 (-> (new-z) (assoc :zettel/trust-level :trusted))
          z2 (zettel/update-zettel z1 {:privacy/classification :restricted})
          z3 (zettel/update-zettel z2 {:fleet/share-scope :team})
          z4 (zettel/update-zettel z3 {:fleet/oss-version "1.0.1"})]
      (is (= (:zettel/revision-id z1) (:zettel/revision-id z4))
          "revision identity intact across operational updates")
      (is (= :trusted (:zettel/trust-level z2)))
      (is (= :trusted (:zettel/trust-level z3)))
      (is (= :trusted (:zettel/trust-level z4))))))

(deftest test-update-zettel-ignores-caller-supplied-trust-level
  (testing "callers cannot stamp :zettel/trust-level via update-zettel changes"
    ;; The full closure: even if a producer attempts to override
    ;; trust through the edit path, update-zettel drops it from
    ;; the merge (it's in derived-fields), then the
    ;; rotation/preserve logic recomputes the right value.
    (let [z1 (new-z)
          ;; Caller tries to stamp :trusted on an untrusted zettel
          ;; with no content change at all
          z2 (zettel/update-zettel z1 {:zettel/trust-level :trusted})]
      (is (= :untrusted (:zettel/trust-level z2))
          "caller-supplied :trusted dropped; trust stays at the previous value"))

    (testing "and the same lock holds when combined with a content edit"
      (let [z1 (-> (new-z) (assoc :zettel/trust-level :trusted))
            z2 (zettel/update-zettel z1 {:zettel/content     "# Edited"
                                         :zettel/trust-level :trusted})]
        (is (= :untrusted (:zettel/trust-level z2))
            "content edit forces :untrusted; spoof attempt at :trusted ignored")))))

(deftest test-update-zettel-backfills-trust-level-on-legacy-zettel
  (testing "a legacy zettel (no :zettel/trust-level) gets :untrusted on first update"
    ;; Same convergence pattern the digest/revision-id backfill uses
    ;; — no explicit migration needed.
    (let [legacy  {:zettel/id      (java.util.UUID/randomUUID)
                   :zettel/uid     "legacy-trust"
                   :zettel/title   "Legacy"
                   :zettel/content "Body."
                   :zettel/type    :rule
                   :zettel/created (java.util.Date.)
                   :zettel/author  "user"}
          updated (zettel/update-zettel legacy {:fleet/oss-version "1.0.0"})]
      (is (= :untrusted (:zettel/trust-level updated))))))

(deftest test-trust-level-schema-rejects-bad-value
  (testing ":zettel/trust-level outside the closed enum fails schema validation"
    (let [z (assoc (new-z) :zettel/trust-level :very-trusted)]
      (is (false? (m/validate schema/Zettel z))))))

(deftest test-trust-level-schema-accepts-both-values
  (testing ":zettel/trust-level :trusted and :untrusted both validate"
    (doseq [t [:trusted :untrusted]]
      (let [z (assoc (new-z) :zettel/trust-level t)]
        (is (m/validate schema/Zettel z))))))
