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

(ns ai.miniforge.event-stream.manifest-test
  "Tests for the BD-2b sub-2 per-workflow manifest module."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.event-stream.manifest :as manifest])
  (:import
   [java.time Instant]
   [java.util UUID]))

;------------------------------------------------------------------------------ Helpers

(defn- with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "manifest-test-"
             (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (f (.toFile dir))
      (finally
        (doseq [file (reverse (file-seq (.toFile dir)))]
          (.delete ^java.io.File file))))))

;; The id is informational at the wire layer (tests round-trip it as a
;; UUID); workflow lifecycle wiring uses a real run id.
(def ^:private test-workflow-id
  (UUID/fromString "00000000-0000-0000-0000-000000000001"))

;; Default lease window for tests is much shorter than the production
;; default so `lease-fresh?` can be exercised against synthetic clock
;; advances without waiting wall-time minutes.
(def ^:private ^:const ^long short-ttl-seconds 5)

;------------------------------------------------------------------------------ Schema

(deftest init-active-produces-valid-manifest
  (let [m (manifest/init-active test-workflow-id)]
    (is (manifest/valid? m)
        (str "init-active should produce a schema-valid manifest, got "
             (pr-str (manifest/explain m))))
    (is (= :active (:status m)))
    (is (= :live (:archive_status m)))
    (is (= :none (:snapshot_status m)))
    (is (true? (:raw_events_retained m)))
    (is (= 0 (get-in m [:snapshot_watermark :workflow_sequence_number])))
    (is (nil? (get-in m [:snapshot_watermark :last_event_id])))))

(deftest valid?-rejects-missing-required-fields
  (is (not (manifest/valid? {})))
  (is (some? (manifest/explain {}))))

(deftest valid?-rejects-unknown-status-enum
  (let [m (assoc (manifest/init-active test-workflow-id)
                 :status :wat)]
    (is (not (manifest/valid? m))
        "unknown :status enum value must fail validation")))

;------------------------------------------------------------------------------ State machine

(deftest legal-archive-transitions-table
  (testing "forward edges"
    (is (manifest/legal-archive-transition? :live :archiving))
    (is (manifest/legal-archive-transition? :archiving :archived))
    (is (manifest/legal-archive-transition? :archived :tombstoned)))
  (testing "rollback :archiving → :live (aborted archive)"
    (is (manifest/legal-archive-transition? :archiving :live)))
  (testing "tombstoned is terminal"
    (is (not (manifest/legal-archive-transition? :tombstoned :live)))
    (is (not (manifest/legal-archive-transition? :tombstoned :archived))))
  (testing "no skipping :archiving"
    (is (not (manifest/legal-archive-transition? :live :archived))))
  (testing "no resurrection from :archived"
    (is (not (manifest/legal-archive-transition? :archived :live)))
    (is (not (manifest/legal-archive-transition? :archived :archiving)))))

(deftest transition-archive-throws-on-illegal-move
  (let [m (manifest/init-active test-workflow-id)]
    (is (= :archiving
           (:archive_status (manifest/transition-archive m :archiving)))
        "live → archiving is the canonical happy path")
    (is (thrown-with-msg? clojure.lang.ExceptionInfo
                          #"Illegal archive_status transition"
                          (manifest/transition-archive m :archived))
        "live → archived must skip :archiving and is illegal")))

(deftest mark-terminal-stamps-completed-at-and-status
  (let [m (manifest/init-active test-workflow-id)]
    (let [done (manifest/mark-terminal m :completed)]
      (is (= :completed (:status done)))
      (is (some? (:completed_at done))
          "completed_at must be stamped on terminal mark"))
    (is (thrown? AssertionError
                 (manifest/mark-terminal m :archived))
        "mark-terminal must reject non-terminal status keywords")))

;------------------------------------------------------------------------------ Lease primitives

(deftest renew-lease-bumps-renewed-at
  ;; Pre-stamp `lease_renewed_at` with a known past instant so the
  ;; test doesn't depend on JVM clock resolution to observe a delta.
  (let [m       (-> (manifest/init-active test-workflow-id
                                          {:ttl-seconds short-ttl-seconds})
                    (assoc-in [:owner :lease_renewed_at]
                              (str (Instant/parse "2026-05-08T00:00:00Z"))))
        before  (get-in m [:owner :lease_renewed_at])
        renewed (manifest/renew-lease m)
        after   (get-in renewed [:owner :lease_renewed_at])]
    (is (not= before after)
        "renew-lease must update lease_renewed_at")
    (is (.isAfter (Instant/parse ^String after)
                  (Instant/parse ^String before)))))

(deftest lease-fresh?-respects-ttl
  (let [m   (manifest/init-active test-workflow-id
                                  {:ttl-seconds short-ttl-seconds})
        ;; Anchor a known renewed-at and probe the freshness window.
        m'  (assoc-in m [:owner :lease_renewed_at]
                      (str (Instant/parse "2026-05-08T00:00:00Z")))]
    (is (manifest/lease-fresh? m' (Instant/parse "2026-05-08T00:00:00Z"))
        "renewed-now is trivially fresh")
    (is (manifest/lease-fresh? m' (Instant/parse "2026-05-08T00:00:04Z"))
        "1s before TTL boundary is fresh")
    (is (manifest/lease-fresh? m' (Instant/parse "2026-05-08T00:00:05Z"))
        "exactly at TTL boundary is fresh (≤, not <)")
    (is (not (manifest/lease-fresh? m' (Instant/parse "2026-05-08T00:00:06Z")))
        "past TTL boundary is stale")))

(deftest owner-pid-alive?-true-for-self
  ;; The lease was just stamped with our own pid + host, so the
  ;; alive-check must return true for this process.
  (let [m (manifest/init-active test-workflow-id)]
    (is (manifest/owner-pid-alive? m))))

(deftest owner-pid-alive?-false-for-different-host
  (let [m (assoc-in (manifest/init-active test-workflow-id)
                    [:owner :host] "definitely-not-this-host.invalid")]
    (is (not (manifest/owner-pid-alive? m))
        "cross-host pid checks are out of scope; treat as not-alive")))

(deftest owner-pid-alive?-false-for-bogus-pid
  (let [m (assoc-in (manifest/init-active test-workflow-id)
                    [:owner :pid] "99999999")]
    (is (not (manifest/owner-pid-alive? m))
        "a pid with no live process must be reported dead")))

;------------------------------------------------------------------------------ Atomic IO

(deftest save-then-load-round-trips
  (with-temp-dir
    (fn [dir]
      (let [m       (manifest/init-active test-workflow-id)
            _       (manifest/save-manifest! dir m)
            loaded  (manifest/load-manifest dir)]
        (is (= (:workflow_id m) (:workflow_id loaded)))
        (is (= (:status m) (:status loaded)))
        (is (= (:archive_status m) (:archive_status loaded)))
        (is (= (:snapshot_status m) (:snapshot_status loaded)))
        (is (= (:owner m) (:owner loaded))
            "owner round-trips byte-for-byte through JSON")))))

(deftest load-manifest-returns-nil-when-absent
  (with-temp-dir
    (fn [dir]
      (is (nil? (manifest/load-manifest dir))))))

(deftest save-manifest!-rejects-invalid-shape
  (with-temp-dir
    (fn [dir]
      (let [bogus (assoc (manifest/init-active test-workflow-id)
                         :status :not-a-status)]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"fails schema validation"
                              (manifest/save-manifest! dir bogus)))))))

(deftest renew-lease!-no-op-when-manifest-absent
  (with-temp-dir
    (fn [dir]
      (is (nil? (manifest/renew-lease! dir))
          "missing manifest is a no-op so a heartbeat tick can race
           archival without crashing"))))

(deftest renew-lease!-bumps-on-disk
  (with-temp-dir
    (fn [dir]
      (let [m       (-> (manifest/init-active test-workflow-id)
                        (assoc-in [:owner :lease_renewed_at]
                                  (str (Instant/parse "2026-05-08T00:00:00Z"))))
            _       (manifest/save-manifest! dir m)
            before  (get-in (manifest/load-manifest dir)
                            [:owner :lease_renewed_at])
            _       (manifest/renew-lease! dir)
            after   (get-in (manifest/load-manifest dir)
                            [:owner :lease_renewed_at])]
        (is (not= before after))
        (is (.isAfter (Instant/parse ^String after)
                      (Instant/parse ^String before)))))))
