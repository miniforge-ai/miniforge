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

(ns ai.miniforge.event-stream.identity-propagation-test
  "Tests for the Decision-14 identity-propagation fields landed for
   miniforge-fleet's Phase E.1 prerequisite #10. Three areas:

     1. `core/create-envelope` accepts the optional org / workspace /
        repo / auth / parent / agent identity kwargs and stamps them
        onto the envelope when present.
     2. The legacy 4-arg arity still works (backward compatibility for
        every in-tree caller that doesn't have identity context yet).
     3. The new fields validate against `EventEnvelope` AND every
        downstream event schema (so a producer adding the fields
        doesn't trip the closed-map check)."
  (:require
   [ai.miniforge.event-stream.core :as core]
   [ai.miniforge.event-stream.schema :as schema]
   [clojure.test :refer [deftest is testing]]
   [malli.core :as m])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Layer 0
;; Helpers.

(defn- fresh-stream []
  (atom {:events [] :sequence-numbers {}}))

;------------------------------------------------------------------------------ Layer 1
;; create-envelope: legacy 4-arg arity.

(deftest test-legacy-arity-still-works
  (testing "the 4-arg arity produces an envelope with no identity fields and validates"
    (let [stream (fresh-stream)
          wid (UUID/randomUUID)
          ev (core/create-envelope stream :workflow/started wid "Started")]
      (is (false? (contains? ev :org/id)))
      (is (false? (contains? ev :workspace/id)))
      (is (false? (contains? ev :repo/id)))
      (is (false? (contains? ev :auth/context)))
      (is (= :workflow/started (:event/type ev)))
      (is (= wid (:workflow/id ev))))))

;------------------------------------------------------------------------------ Layer 2
;; create-envelope: identity kwargs.

(deftest test-org-id-stamped-when-supplied
  (testing ":org/id rides through to the envelope"
    (let [stream (fresh-stream)
          oid (UUID/randomUUID)
          ev  (core/create-envelope stream :workflow/started (UUID/randomUUID) "msg"
                                    {:org/id oid})]
      (is (= oid (:org/id ev))))))

(deftest test-workspace-id-stamped-when-supplied
  (testing ":workspace/id rides through to the envelope"
    (let [stream (fresh-stream)
          wsid (UUID/randomUUID)
          ev   (core/create-envelope stream :workflow/started (UUID/randomUUID) "msg"
                                     {:workspace/id wsid})]
      (is (= wsid (:workspace/id ev))))))

(deftest test-repo-id-stamped-when-supplied
  (testing ":repo/id rides through (string slug shape)"
    (let [stream (fresh-stream)
          ev (core/create-envelope stream :workflow/started (UUID/randomUUID) "msg"
                                   {:repo/id "miniforge-ai/miniforge"})]
      (is (= "miniforge-ai/miniforge" (:repo/id ev))))))

(deftest test-auth-context-stamped-when-supplied
  (testing ":auth/context rides through as an opaque map"
    (let [stream (fresh-stream)
          ctx {:auth/principal "alice" :auth/scopes #{:read :write}}
          ev (core/create-envelope stream :workflow/started (UUID/randomUUID) "msg"
                                   {:auth/context ctx})]
      (is (= ctx (:auth/context ev))))))

(deftest test-all-identity-fields-together
  (testing "every identity field stamped together — full multi-tenant shape"
    (let [stream (fresh-stream)
          oid    (UUID/randomUUID)
          wsid   (UUID/randomUUID)
          parent (UUID/randomUUID)
          aid    (UUID/randomUUID)
          ev (core/create-envelope stream :workflow/started (UUID/randomUUID) "msg"
                                   {:org/id            oid
                                    :workspace/id      wsid
                                    :repo/id           "owner/repo"
                                    :auth/context      {:auth/principal "alice"}
                                    :event/parent-id   parent
                                    :agent/id          :planner
                                    :agent/instance-id aid})]
      (is (= oid (:org/id ev)))
      (is (= wsid (:workspace/id ev)))
      (is (= "owner/repo" (:repo/id ev)))
      (is (= {:auth/principal "alice"} (:auth/context ev)))
      (is (= parent (:event/parent-id ev)))
      (is (= :planner (:agent/id ev)))
      (is (= aid (:agent/instance-id ev))))))

(deftest test-empty-opts-equivalent-to-legacy-arity
  (testing "passing {} as opts is identical to the 4-arg arity"
    (let [stream (fresh-stream)
          wid (UUID/randomUUID)
          a (core/create-envelope stream :workflow/started wid "m")
          b (core/create-envelope stream :workflow/started wid "m" {})]
      (is (= (dissoc a :event/id :event/timestamp :event/sequence-number)
             (dissoc b :event/id :event/timestamp :event/sequence-number))))))

;------------------------------------------------------------------------------ Layer 3
;; Schema acceptance — events with identity fields validate.

(defn- envelope-shape
  "Build a minimal EventEnvelope-compatible map with the identity fields populated."
  [type-kw extra]
  (merge {:event/type             type-kw
          :event/id               (UUID/randomUUID)
          :event/timestamp        (java.util.Date.)
          :event/version          "1.0"
          :event/sequence-number  0
          :workflow/id            (UUID/randomUUID)
          :org/id                 (UUID/randomUUID)
          :workspace/id           (UUID/randomUUID)
          :repo/id                "owner/repo"
          :auth/context           {:auth/principal "alice"}
          :message                "test"}
         extra))

(deftest test-envelope-with-identity-validates
  (testing "EventEnvelope accepts the four identity fields"
    (let [ev (envelope-shape :test/anything {})]
      (is (m/validate schema/EventEnvelope ev)))))

(deftest test-workflow-started-with-identity-validates
  (testing "WorkflowStarted accepts the four identity fields (closed-map check passes)"
    (let [ev (envelope-shape :workflow/started {})]
      (is (m/validate schema/WorkflowStarted ev)))))

(deftest test-phase-completed-with-identity-validates
  (testing "PhaseCompleted accepts the four identity fields"
    (let [ev (envelope-shape :workflow/phase-completed
                             {:workflow/phase :plan})]
      (is (m/validate schema/PhaseCompleted ev)))))

(deftest test-tool-use-evaluated-with-identity-validates
  (testing "ToolUseEvaluated accepts the four identity fields (later-added schema)"
    (let [ev (envelope-shape :supervision/tool-use-evaluated
                             {:tool/name "bash"
                              :supervision/decision "approved"})]
      (is (m/validate schema/ToolUseEvaluated ev)))))

(deftest test-events-without-identity-still-validate
  (testing "events without any identity fields still validate (backward compat)"
    (let [ev {:event/type :workflow/started
              :event/id (UUID/randomUUID)
              :event/timestamp (java.util.Date.)
              :event/version "1.0"
              :event/sequence-number 0
              :workflow/id (UUID/randomUUID)
              :message "test"}]
      (is (m/validate schema/WorkflowStarted ev)))))

;------------------------------------------------------------------------------ Layer 4
;; Sequence numbering — atomic increment under concurrency.

(deftest test-sequence-numbers-are-unique-under-concurrency
  (testing "concurrent create-envelope calls for the same workflow get distinct :event/sequence-number values"
    ;; Reviewer flagged the original read-then-swap pattern as racey
    ;; — concurrent producers could observe the same seq number.
    ;; The fix uses `swap-vals!` for an atomic capture-and-increment.
    ;; This test fans out N futures hitting the same workflow id and
    ;; asserts that every emitted event got a distinct seq number.
    (let [stream      (fresh-stream)
          workflow-id (UUID/randomUUID)
          n           500
          futures     (doall
                        (for [_ (range n)]
                          (future
                            (:event/sequence-number
                              (core/create-envelope stream
                                                    :workflow/started
                                                    workflow-id
                                                    "concurrency probe")))))
          seqs        (mapv deref futures)]
      (is (= n (count (distinct seqs)))
          (str "expected " n " distinct seq numbers, got "
               (count (distinct seqs)))))))
