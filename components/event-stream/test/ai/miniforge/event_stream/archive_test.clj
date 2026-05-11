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

(ns ai.miniforge.event-stream.archive-test
  "Tests for the BD-2b sub-3b atomic archive operation."
  (:require
   [clojure.test :refer [deftest is testing]]
   [clojure.java.io :as io]
   [ai.miniforge.event-stream.archive :as archive]
   [ai.miniforge.event-stream.manifest :as manifest]
   [ai.miniforge.event-stream.sinks :as sinks])
  (:import
   [java.util UUID]))

;------------------------------------------------------------------------------ Helpers

(defn- with-temp-base [f]
  (let [dir (java.nio.file.Files/createTempDirectory
             "archive-test-"
             (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (f (.toFile dir))
      (finally
        (doseq [file (reverse (file-seq (.toFile dir)))]
          (try (.delete ^java.io.File file) (catch Exception _)))))))

(def ^:private test-workflow-id
  (UUID/fromString "00000000-0000-0000-0000-000000000abc"))

(defn- seed-live-workflow!
  "Set up a live/{wid}/ directory with a fresh :active / :live
   manifest. Returns the live dir. Optional :extras let tests add
   sentinel event files so the rename can be verified end-to-end."
  ([base-dir wid] (seed-live-workflow! base-dir wid {}))
  ([base-dir wid {:keys [event-files snapshot-tmp]}]
   (let [live (sinks/live-workflow-dir base-dir wid)]
     (.mkdirs live)
     (manifest/save-manifest! live (manifest/init-active wid))
     (doseq [name event-files]
       (spit (io/file live name) (str "sentinel " name)))
     (when snapshot-tmp
       (spit (io/file live "snapshot.transit.json.tmp") snapshot-tmp))
     live)))

(defn- archived-dir-for [base wid]
  (sinks/archived-workflow-dir base wid))

;------------------------------------------------------------------------------ archive-workflow! happy path

(deftest archive-workflow!-renames-live-to-archived
  (with-temp-base
    (fn [base]
      (seed-live-workflow! base test-workflow-id
                           {:event-files ["evt-1.transit.json" "evt-2.transit.json"]})
      (archive/archive-workflow! test-workflow-id {:base-dir base})
      (let [live     (sinks/live-workflow-dir base test-workflow-id)
            archived (archived-dir-for base test-workflow-id)]
        (is (false? (.exists live))
            "live dir must be gone after the atomic rename")
        (is (.exists archived))
        (is (.exists (io/file archived "evt-1.transit.json"))
            "event files travel with the directory rename")
        (is (.exists (io/file archived "evt-2.transit.json")))
        (is (.exists (io/file archived "archived.marker"))
            "archived.marker is touched as step 7")))))

(deftest archive-workflow!-transitions-manifest-to-archived
  (with-temp-base
    (fn [base]
      (seed-live-workflow! base test-workflow-id)
      (archive/archive-workflow! test-workflow-id {:base-dir base})
      (let [archived (archived-dir-for base test-workflow-id)
            m (manifest/load-manifest archived)]
        (is (= :archived (:archive_status m)))
        (is (some? (:archived_at m))
            "archived_at must be stamped at step 7")))))

(deftest archive-workflow!-accepts-snapshot-watermark
  (with-temp-base
    (fn [base]
      (seed-live-workflow! base test-workflow-id)
      (archive/archive-workflow! test-workflow-id
                                 {:base-dir base
                                  :snapshot-watermark
                                  {:workflow_sequence_number 42
                                   :last_event_id "018f3a9c8e4b12d0"}})
      (let [archived (archived-dir-for base test-workflow-id)
            m (manifest/load-manifest archived)]
        (is (= 42 (get-in m [:snapshot_watermark :workflow_sequence_number])))
        (is (= "018f3a9c8e4b12d0"
               (get-in m [:snapshot_watermark :last_event_id])))))))

(deftest archive-workflow!-tolerates-absent-snapshot-tmp
  ;; Sub-3c will write `snapshot.transit.json.tmp` before invoking
  ;; archive; sub-3b must not require it. Without the tmp, the
  ;; archive completes and `snapshot_status` stays `:none`.
  (with-temp-base
    (fn [base]
      (seed-live-workflow! base test-workflow-id)
      (archive/archive-workflow! test-workflow-id {:base-dir base})
      (let [archived (archived-dir-for base test-workflow-id)]
        (is (false? (.exists (io/file archived "snapshot.transit.json")))
            "no snapshot file when no tmp was staged")
        (is (= :none (:snapshot_status (manifest/load-manifest archived))))))))

(deftest archive-workflow!-renames-snapshot-tmp-to-final
  (with-temp-base
    (fn [base]
      (seed-live-workflow! base test-workflow-id
                           {:snapshot-tmp "placeholder snapshot payload"})
      (archive/archive-workflow! test-workflow-id {:base-dir base})
      (let [archived (archived-dir-for base test-workflow-id)
            snapshot (io/file archived "snapshot.transit.json")
            tmp      (io/file archived "snapshot.transit.json.tmp")]
        (is (.exists snapshot)
            "snapshot.transit.json must exist after the rename")
        (is (= "placeholder snapshot payload" (slurp snapshot))
            "rename preserves the staged contents")
        (is (false? (.exists tmp))
            "tmp must be gone after the rename")))))

(deftest archive-workflow!-throws-when-no-manifest
  (with-temp-base
    (fn [base]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"no manifest at live dir"
                            (archive/archive-workflow!
                             test-workflow-id {:base-dir base}))))))

;------------------------------------------------------------------------------ recover-incomplete-archive!

(deftest recover-incomplete-archive!-finishes-archiving-state
  ;; Live dir still exists, manifest is at :archiving. Simulates a
  ;; crash between steps 4 and 6. Recovery should atomic-rename and
  ;; complete the marker.
  (with-temp-base
    (fn [base]
      (let [live (seed-live-workflow! base test-workflow-id)
            m    (manifest/load-manifest live)]
        ;; Stage manifest into :archiving as if step 3 had run.
        (manifest/save-manifest! live (manifest/transition-archive m :archiving))
        (archive/recover-incomplete-archive! base test-workflow-id)
        (let [archived (archived-dir-for base test-workflow-id)]
          (is (false? (.exists live))
              "live dir must be moved by the recovery")
          (is (.exists (io/file archived "archived.marker"))
              "marker must be touched by the recovery")
          (is (= :archived (:archive_status (manifest/load-manifest archived)))))))))

(deftest recover-incomplete-archive!-finishes-missing-marker
  ;; Live dir is already gone, archived dir exists but no marker.
  ;; Simulates a crash between steps 6 and 7.
  (with-temp-base
    (fn [base]
      (let [live (seed-live-workflow! base test-workflow-id)
            m    (manifest/load-manifest live)
            archived (archived-dir-for base test-workflow-id)]
        ;; Walk the manifest into :archiving, do step 6 manually
        ;; (rename live → archived) but skip step 7 (no marker).
        (manifest/save-manifest! live (manifest/transition-archive m :archiving))
        (.mkdirs (.getParentFile archived))
        (.renameTo live archived)
        (is (false? (.exists (io/file archived "archived.marker"))))
        (archive/recover-incomplete-archive! base test-workflow-id)
        (is (.exists (io/file archived "archived.marker"))
            "missing marker is touched by the recovery")
        (is (= :archived (:archive_status (manifest/load-manifest archived))))))))

(deftest recover-incomplete-archive!-is-no-op-when-no-recovery-needed
  ;; Both dirs absent (or both clean) — recovery returns nil and
  ;; doesn't error.
  (with-temp-base
    (fn [base]
      (is (nil? (archive/recover-incomplete-archive! base test-workflow-id))))))

;------------------------------------------------------------------------------ scan-incomplete-archives / recover-all-incomplete!

(deftest scan-incomplete-archives-classifies-correctly
  (with-temp-base
    (fn [base]
      (let [wid-archiving  (UUID/fromString "00000000-0000-0000-0000-000000000001")
            wid-no-marker  (UUID/fromString "00000000-0000-0000-0000-000000000002")
            wid-clean-live (UUID/fromString "00000000-0000-0000-0000-000000000003")]
        ;; A: live/{wid}/ with manifest archive_status=:archiving.
        (let [d (seed-live-workflow! base wid-archiving)
              m (manifest/load-manifest d)]
          (manifest/save-manifest! d (manifest/transition-archive m :archiving)))
        ;; B: archived/{wid}/ exists, no marker.
        (let [d (seed-live-workflow! base wid-no-marker)
              m (manifest/load-manifest d)
              archived (archived-dir-for base wid-no-marker)]
          (manifest/save-manifest! d (manifest/transition-archive m :archiving))
          (.mkdirs (.getParentFile archived))
          (.renameTo d archived))
        ;; C: clean live workflow, archive_status=:live — no recovery.
        (seed-live-workflow! base wid-clean-live)
        (let [scanned (set (archive/scan-incomplete-archives base))]
          (is (contains? scanned
                         {:workflow-id (str wid-archiving)
                          :state :archiving-in-live}))
          (is (contains? scanned
                         {:workflow-id (str wid-no-marker)
                          :state :archived-no-marker}))
          (is (= 2 (count scanned))
              "clean live workflow must not show up as incomplete"))))))

(deftest recover-all-incomplete!-runs-recovery-for-each
  (with-temp-base
    (fn [base]
      (let [wid-a (UUID/fromString "00000000-0000-0000-0000-0000000000aa")
            wid-b (UUID/fromString "00000000-0000-0000-0000-0000000000bb")]
        (let [d (seed-live-workflow! base wid-a)
              m (manifest/load-manifest d)]
          (manifest/save-manifest! d (manifest/transition-archive m :archiving)))
        (let [d (seed-live-workflow! base wid-b)
              m (manifest/load-manifest d)
              archived (archived-dir-for base wid-b)]
          (manifest/save-manifest! d (manifest/transition-archive m :archiving))
          (.mkdirs (.getParentFile archived))
          (.renameTo d archived))
        (let [recovered (set (archive/recover-all-incomplete! base))]
          (is (= #{(str wid-a) (str wid-b)} recovered)))
        (is (.exists (io/file (archived-dir-for base wid-a) "archived.marker")))
        (is (.exists (io/file (archived-dir-for base wid-b) "archived.marker")))))))
