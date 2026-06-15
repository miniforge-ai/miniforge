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

(ns ai.miniforge.event-stream.repo-index-events-test
  "Constructor tests for the two repo-index intelligence event types (RN-19/20):
     :repo-index/quality-measured   (repo-index-quality-measured)
     :repo-index/coverage-changed   (repo-index-coverage-changed)

   Coverage:
     1. Happy path — correct :event/type, required domain fields, :workflow/id nil.
     2. Optional :tree-sha present → :index/tree-sha assoc'd onto the map.
     3. Optional :tree-sha absent → key not in map.
     4. :message is non-nil and non-blank.
     5. Envelope fields (id, timestamp, version, sequence-number) are present and typed.
     6. Schema conformance via malli for both event types."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.string :as str]
   [malli.core :as m]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.schema :as schema]))

;------------------------------------------------------------------------------ Layer 0
;; Named constants — no magic literals in test bodies

(def ^:private index-id          "owner/repo")
(def ^:private quality-score     0.87)
(def ^:private staleness-ms      45000)
(def ^:private coverage          0.95)
(def ^:private previous-coverage 0.80)
(def ^:private tree-sha          "a1b2c3d4e5f6a1b2c3d4e5f6a1b2c3d4e5f6a1b2")

(defn- stream [] (es/create-event-stream))

;------------------------------------------------------------------------------ Layer 1
;; repo-index-quality-measured happy path

(deftest quality-measured-event-type-test
  (testing "emits :repo-index/quality-measured event type"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage)]
      (is (= :repo-index/quality-measured (:event/type ev))))))

(deftest quality-measured-workflow-id-nil-test
  (testing ":workflow/id is nil — index re-scoring runs outside any workflow"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage)]
      (is (nil? (:workflow/id ev))))))

(deftest quality-measured-domain-fields-test
  (testing "required domain fields carry the supplied values"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage)]
      (is (= index-id      (:index/id ev)))
      (is (= quality-score (:index/quality-score ev)))
      (is (= staleness-ms  (:index/staleness-ms ev)))
      (is (= coverage      (:index/coverage ev))))))

(deftest quality-measured-message-non-blank-test
  (testing ":message is a non-nil, non-blank string"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage)]
      (is (string? (:message ev)))
      (is (not (str/blank? (:message ev)))))))

(deftest quality-measured-envelope-fields-test
  (testing "standard envelope fields are present and correctly typed"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage)]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev))))))

;------------------------------------------------------------------------------ Layer 1
;; repo-index-quality-measured optional :tree-sha

(deftest quality-measured-tree-sha-present-test
  (testing "optional :tree-sha opt → :index/tree-sha is assoc'd"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage
              {:tree-sha tree-sha})]
      (is (= tree-sha (:index/tree-sha ev))))))

(deftest quality-measured-tree-sha-absent-test
  (testing "omitting opts → :index/tree-sha key not in map"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage)]
      (is (not (contains? ev :index/tree-sha))))))

(deftest quality-measured-empty-opts-no-tree-sha-test
  (testing "empty opts map → :index/tree-sha key not in map"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage {})]
      (is (not (contains? ev :index/tree-sha))))))

;------------------------------------------------------------------------------ Layer 1
;; repo-index-quality-measured schema conformance

(deftest quality-measured-schema-conforms-test
  (testing "emitted event passes RepoIndexQualityMeasured malli schema"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage)]
      (is (m/validate schema/RepoIndexQualityMeasured ev)))))

(deftest quality-measured-schema-with-tree-sha-conforms-test
  (testing "event with optional :tree-sha passes schema"
    (let [ev (es/repo-index-quality-measured
              (stream) index-id quality-score staleness-ms coverage
              {:tree-sha tree-sha})]
      (is (m/validate schema/RepoIndexQualityMeasured ev)))))

;------------------------------------------------------------------------------ Layer 2
;; repo-index-coverage-changed happy path

(deftest coverage-changed-event-type-test
  (testing "emits :repo-index/coverage-changed event type"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage)]
      (is (= :repo-index/coverage-changed (:event/type ev))))))

(deftest coverage-changed-workflow-id-nil-test
  (testing ":workflow/id is nil — coverage re-evaluation is background work"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage)]
      (is (nil? (:workflow/id ev))))))

(deftest coverage-changed-domain-fields-test
  (testing "required domain fields carry the supplied values"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage)]
      (is (= index-id          (:index/id ev)))
      (is (= coverage          (:index/coverage ev)))
      (is (= previous-coverage (:index/previous-coverage ev))))))

(deftest coverage-changed-message-non-blank-test
  (testing ":message is a non-nil, non-blank string"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage)]
      (is (string? (:message ev)))
      (is (not (str/blank? (:message ev)))))))

(deftest coverage-changed-envelope-fields-test
  (testing "standard envelope fields are present and correctly typed"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage)]
      (is (uuid? (:event/id ev)))
      (is (inst? (:event/timestamp ev)))
      (is (string? (:event/version ev)))
      (is (int? (:event/sequence-number ev))))))

;------------------------------------------------------------------------------ Layer 2
;; repo-index-coverage-changed optional :tree-sha

(deftest coverage-changed-tree-sha-present-test
  (testing "optional :tree-sha opt → :index/tree-sha is assoc'd"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage
              {:tree-sha tree-sha})]
      (is (= tree-sha (:index/tree-sha ev))))))

(deftest coverage-changed-tree-sha-absent-test
  (testing "omitting opts → :index/tree-sha key not in map"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage)]
      (is (not (contains? ev :index/tree-sha))))))

(deftest coverage-changed-empty-opts-no-tree-sha-test
  (testing "empty opts map → :index/tree-sha key not in map"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage {})]
      (is (not (contains? ev :index/tree-sha))))))

;------------------------------------------------------------------------------ Layer 2
;; repo-index-coverage-changed schema conformance

(deftest coverage-changed-schema-conforms-test
  (testing "emitted event passes RepoIndexCoverageChanged malli schema"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage)]
      (is (m/validate schema/RepoIndexCoverageChanged ev)))))

(deftest coverage-changed-schema-with-tree-sha-conforms-test
  (testing "event with optional :tree-sha passes schema"
    (let [ev (es/repo-index-coverage-changed
              (stream) index-id coverage previous-coverage
              {:tree-sha tree-sha})]
      (is (m/validate schema/RepoIndexCoverageChanged ev)))))

;------------------------------------------------------------------------------ Layer 3
;; Interface delegation — public API is reachable through interface.clj

(deftest quality-measured-accessible-via-interface-test
  (testing "repo-index-quality-measured is accessible through the public interface"
    (is (fn? es/repo-index-quality-measured))))

(deftest coverage-changed-accessible-via-interface-test
  (testing "repo-index-coverage-changed is accessible through the public interface"
    (is (fn? es/repo-index-coverage-changed))))
