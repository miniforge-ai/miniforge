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

(ns ai.miniforge.event-stream.zettel-promoted-test
  "Tests for the `zettel-promoted` event constructor + schema (added
   for miniforge-fleet's Phase E.3 outbox path).

   Three areas:
     1. The constructor maps a zettel + version pin to a valid
        envelope-shaped event the schema accepts.
     2. Optional Fleet-share intent (`:fleet/shareable` /
        `:fleet/share-scope` / `:privacy/classification`) rides
        through when present on the source zettel.
     3. The event-type-registry knows about `:zettel/promoted` and
        the schema's privacy config classifies it as `:internal`."
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.event-type-registry :as registry]
   [ai.miniforge.event-stream.schema :as schema]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers.

(defn- fresh-stream []
  (atom {:events [] :sequence-numbers {}}))

(defn- trusted-zettel
  "Build a Decision-6-compliant zettel literal with optional Fleet-
   share intent. Skips the real `knowledge/create-zettel` constructor
   so this test stays inside the event-stream brick's dep boundary
   (event-stream does not require ai.miniforge.knowledge). The
   constructor's behavior is exercised on the knowledge side."
  [& {:keys [shareable scope classification]}]
  (cond-> {:zettel/id          (UUID/randomUUID)
           :zettel/revision-id (UUID/randomUUID)
           :zettel/digest      "deadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeefdeadbeef"
           :zettel/uid         "test-uid"
           :zettel/title       "Test Title"
           :zettel/content     "# Body of the test zettel.\n\nMore content."
           :zettel/type        :rule
           :zettel/created     (java.util.Date.)
           :zettel/author      "user"}
    (some? shareable)  (assoc :fleet/shareable shareable)
    scope              (assoc :fleet/share-scope scope)
    classification     (assoc :privacy/classification classification)))

;------------------------------------------------------------------------------ Layer 1
;; Constructor: minimal happy path.

(deftest test-zettel-promoted-stamps-envelope-and-revision-fields
  (testing "constructor stamps the envelope plus the Decision-6 revision triple + content"
    (let [stream      (fresh-stream)
          workflow-id (UUID/randomUUID)
          z           (trusted-zettel)
          ev          (core/zettel-promoted stream workflow-id z "1.0.0")]
      ;; Envelope
      (is (= :zettel/promoted (:event/type ev)))
      (is (uuid? (:event/id ev)))
      (is (= workflow-id (:workflow/id ev)))
      (is (string? (:message ev)))
      ;; Revision triple
      (is (= (:zettel/id z)          (:zettel/id ev)))
      (is (= (:zettel/revision-id z) (:zettel/revision-id ev)))
      (is (= (:zettel/digest z)      (:zettel/digest ev)))
      ;; Content
      (is (= (:zettel/uid z)     (:zettel/uid ev)))
      (is (= (:zettel/title z)   (:zettel/title ev)))
      (is (= (:zettel/content z) (:zettel/content ev)))
      (is (= (:zettel/type z)    (:zettel/type ev)))
      ;; Version pin
      (is (= "1.0.0" (:fleet/oss-version ev))))))

(deftest test-zettel-promoted-validates-against-schema
  (testing "the constructor's output validates against ZettelPromoted"
    (let [ev (core/zettel-promoted (fresh-stream) (UUID/randomUUID)
                                   (trusted-zettel) "1.0.0")]
      (is (m/validate schema/ZettelPromoted ev)))))

;------------------------------------------------------------------------------ Layer 2
;; Constructor: optional Fleet-share intent rides through.

(deftest test-fleet-shareable-rides-through
  (testing ":fleet/shareable from the zettel rides onto the event"
    (let [z  (trusted-zettel :shareable true :scope :org :classification :internal)
          ev (core/zettel-promoted (fresh-stream) (UUID/randomUUID) z "1.0.0")]
      (is (true? (:fleet/shareable ev)))
      (is (= :org (:fleet/share-scope ev)))
      (is (= :internal (:privacy/classification ev)))
      (is (m/validate schema/ZettelPromoted ev)))))

(deftest test-default-share-intent-omitted
  (testing "a zettel without share-intent fields produces an event without those keys"
    (let [z  (trusted-zettel)
          ev (core/zettel-promoted (fresh-stream) (UUID/randomUUID) z "1.0.0")]
      (is (false? (contains? ev :fleet/shareable)))
      (is (false? (contains? ev :fleet/share-scope)))
      (is (false? (contains? ev :privacy/classification)))
      (is (m/validate schema/ZettelPromoted ev)))))

;------------------------------------------------------------------------------ Layer 3
;; Constructor: identity propagation kwargs ride through via opts.

(deftest test-identity-propagation-via-opts
  (testing "envelope opts (org/id, workspace/id, repo/id, auth/context) ride through"
    (let [oid    (UUID/randomUUID)
          wsid   (UUID/randomUUID)
          ev     (core/zettel-promoted (fresh-stream) (UUID/randomUUID)
                                       (trusted-zettel) "1.0.0"
                                       {:org/id oid
                                        :workspace/id wsid
                                        :repo/id "owner/repo"
                                        :auth/context {:auth/principal "alice"}})]
      (is (= oid (:org/id ev)))
      (is (= wsid (:workspace/id ev)))
      (is (= "owner/repo" (:repo/id ev)))
      (is (= {:auth/principal "alice"} (:auth/context ev)))
      (is (m/validate schema/ZettelPromoted ev)))))

;------------------------------------------------------------------------------ Layer 4
;; Schema-level rejection.

(deftest test-schema-rejects-event-without-version-pin
  (testing "ZettelPromoted requires :fleet/oss-version (Decision 13)"
    (let [ev (core/zettel-promoted (fresh-stream) (UUID/randomUUID)
                                   (trusted-zettel) "1.0.0")]
      (is (false? (m/validate schema/ZettelPromoted (dissoc ev :fleet/oss-version)))))))

(deftest test-schema-rejects-event-without-revision-triple
  (testing "ZettelPromoted requires the Decision-6 revision triple"
    (let [ev (core/zettel-promoted (fresh-stream) (UUID/randomUUID)
                                   (trusted-zettel) "1.0.0")]
      (is (false? (m/validate schema/ZettelPromoted (dissoc ev :zettel/digest))))
      (is (false? (m/validate schema/ZettelPromoted (dissoc ev :zettel/revision-id)))))))

(deftest test-schema-rejects-uppercase-digest
  (testing ":zettel/digest must be lowercase 64-char hex (matches the OSS knowledge schema regex)"
    (let [ev (assoc (core/zettel-promoted (fresh-stream) (UUID/randomUUID)
                                          (trusted-zettel) "1.0.0")
                    :zettel/digest
                    "DEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEFDEADBEEF")]
      (is (false? (m/validate schema/ZettelPromoted ev))))))

(deftest test-schema-rejects-unknown-zettel-type
  (testing ":zettel/type is constrained to the closed enum mirroring knowledge.schema/ZettelType"
    ;; Catches typos / drift between the OSS knowledge enum and this
    ;; mirrored copy. A new type added to knowledge.schema/ZettelType
    ;; must be mirrored here before it can ride the outbox stream.
    (let [base (core/zettel-promoted (fresh-stream) (UUID/randomUUID)
                                     (trusted-zettel) "1.0.0")]
      (doseq [bad-type [:not-a-real-type :pattern :hypothesis]]
        (is (false? (m/validate schema/ZettelPromoted (assoc base :zettel/type bad-type)))
            (str ":zettel/type " bad-type " should be rejected"))))))

(deftest test-schema-accepts-every-knowledge-zettel-type
  (testing "every value the OSS knowledge schema enumerates is accepted here"
    ;; Pin the sync requirement: if knowledge.schema/ZettelType
    ;; gains a value, this test surfaces the gap in the mirrored
    ;; enum the next time the suite runs.
    (let [base (core/zettel-promoted (fresh-stream) (UUID/randomUUID)
                                     (trusted-zettel) "1.0.0")]
      (doseq [t [:rule :concept :learning :example :hub :question :decision]]
        (is (m/validate schema/ZettelPromoted (assoc base :zettel/type t))
            (str ":zettel/type " t " should be accepted"))))))

;------------------------------------------------------------------------------ Layer 5
;; Registry + privacy classification.

(deftest test-event-type-registry-knows-about-zettel-promoted
  (testing "the constructor + serialised string are registered"
    (let [entry (->> registry/event-type-registry
                     (some #(when (= "zettel-promoted" (:constructor %)) %)))]
      (is (some? entry) "registry should carry a zettel-promoted entry")
      (is (= :zettel/promoted (:event-type entry)))
      (is (= "zettel/promoted" (:json-string entry))))))

(deftest test-default-privacy-classifies-zettel-promoted-internal
  (testing "default privacy is :internal — Fleet's gates decide cross-instance share"
    (is (= :internal (schema/event-privacy :zettel/promoted)))))
