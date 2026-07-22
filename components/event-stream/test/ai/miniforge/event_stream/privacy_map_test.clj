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

(ns ai.miniforge.event-stream.privacy-map-test
  "Regression tests for explicit entries in `default-event-privacy`.

   Each test pins the privacy level of an event type that has a live
   constructor in core.clj.  The assertions are intentionally specific
   (not testing the `:internal` default fallback) so that a future
   change to any individual entry is caught by the suite rather than
   silently inheriting whatever the fallback becomes."
  (:require
   [ai.miniforge.event-stream.schema :as schema]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Phase heartbeat

(deftest phase-heartbeat-privacy-is-internal
  (testing ":workflow/phase-heartbeat carries phase metrics — :internal"
    (is (= :internal (schema/event-privacy :workflow/phase-heartbeat)))))

;------------------------------------------------------------------------------ Layer 1
;; Milestone sub-lifecycle

(deftest milestone-started-privacy-is-public
  (testing ":phase/milestone-started is a lifecycle event — :public like :workflow/milestone-reached"
    (is (= :public (schema/event-privacy :phase/milestone-started)))))

(deftest milestone-completed-privacy-is-public
  (testing ":phase/milestone-completed is a lifecycle event — :public"
    (is (= :public (schema/event-privacy :phase/milestone-completed)))))

(deftest milestone-failed-privacy-is-public
  (testing ":phase/milestone-failed is :public — consistent with :workflow/failed and :gate/failed"
    (is (= :public (schema/event-privacy :phase/milestone-failed)))))

;------------------------------------------------------------------------------ Layer 2
;; Inter-agent messages

(deftest agent-message-sent-privacy-is-internal
  (testing ":agent/message-sent carries routing metadata — :internal like :agent/status"
    (is (= :internal (schema/event-privacy :agent/message-sent)))))

(deftest agent-message-received-privacy-is-internal
  (testing ":agent/message-received carries routing metadata — :internal"
    (is (= :internal (schema/event-privacy :agent/message-received)))))

(deftest operator-intervention-anomaly-privacy-is-confidential
  (testing "operator anomalies can carry detailed errors — :confidential"
    (is (= :confidential
           (schema/event-privacy :operator/intervention-anomaly)))))
