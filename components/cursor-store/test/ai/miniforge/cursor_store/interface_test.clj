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
(ns ai.miniforge.cursor-store.interface-test
  (:require [clojure.java.io :as io]
            [clojure.test :refer [deftest is testing]]
            [ai.miniforge.connector-http.interface :as connector-http]
            [ai.miniforge.cursor-store.interface :as sut]
            [ai.miniforge.logging.interface :as log])
  (:import [java.time Instant]
           [java.util Date]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} tmp-pipeline-path []
  (str (System/getProperty "java.io.tmpdir")
       "/cursor-store-test-"
       (random-uuid)
       "/pipelines/pipeline.edn"))

(def ^{:stratum 0} ^:private logger (log/create-logger {:min-level :debug :output :human}))

(def ^{:stratum 0} ^:private watermark-iso "2024-01-15T10:00:00Z")

(defn- ^{:stratum 0} record-timestamp [record]
  (or (:updated_at record) (:created_at record)))

(defn- ^{:stratum 0} stage-cursor
  "One run's cursor map for a single stage, with the per-run
   identifiers a real run would generate."
  [stage-name value]
  {(random-uuid) {:stage/name          stage-name
                  :stage/connector-ref (random-uuid)
                  :stage/schema-name   stage-name
                  :cursor {:cursor/type :offset :cursor/value value}
                  :cursor/updated-at   (Instant/now)}})

;------------------------------------------------------------------------------ Layer 1

;; ---------------------------------------------------------------------------
;; load-cursors
(deftest ^{:stratum 1} load-cursors-first-run-test
  (testing "Returns empty map when no cursor file exists yet"
    (let [result (sut/load-cursors logger (tmp-pipeline-path))]
      (is (:success? result))
      (is (= {} (:cursors result))))))

;; ---------------------------------------------------------------------------
;; round-trip
(deftest ^{:stratum 1} round-trip-test
  (testing "save then load preserves cursor data with stable key"
    (let [path      (tmp-pipeline-path)
          stage-id  (random-uuid)
          stage     "Ingest Issues"
          schema    "issues"
          instant   (Instant/now)
          cursor-map {stage-id {:stage/id            stage-id
                                :stage/name          stage
                                :stage/connector-ref (random-uuid)
                                :stage/schema-name   schema
                                :cursor              {:cursor/type  :offset
                                                      :cursor/value 42}
                                :cursor/updated-at   instant}}]
      (is (:success? (sut/save-cursors logger path cursor-map)))
      (let [result (sut/load-cursors logger path)]
        (is (:success? result))
        (let [loaded (get (:cursors result) [stage schema])]
          (is (some? loaded))
          (is (= :offset (get-in loaded [:cursor :cursor/type])))
          (is (= 42 (get-in loaded [:cursor :cursor/value])))
          ;; :cursor/updated-at is stored as an ISO-8601 string (Instant not EDN-printable)
          (is (string? (:cursor/updated-at loaded)))
          (is (= (str instant) (:cursor/updated-at loaded))))))))

(deftest ^{:stratum 1} multi-stage-round-trip-test
  (testing "Two stages sharing one connector keep separate entries"
    (let [path      (tmp-pipeline-path)
          id-1      (random-uuid)
          id-2      (random-uuid)
          ;; Both stages read through the same connector, as an
          ;; ingest-issues/ingest-merge-requests pack does.
          conn-ref  (random-uuid)
          cursor-map {id-1 {:stage/name          "Ingest Issues"
                            :stage/connector-ref conn-ref
                            :stage/schema-name   "issues"
                            :cursor              {:cursor/type :offset :cursor/value 10}
                            :cursor/updated-at   (Instant/now)}
                      id-2 {:stage/name          "Ingest Merge Requests"
                            :stage/connector-ref conn-ref
                            :stage/schema-name   "merge-requests"
                            :cursor              {:cursor/type :offset :cursor/value 5}
                            :cursor/updated-at   (Instant/now)}}]
      (sut/save-cursors logger path cursor-map)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 2 (count loaded)))
        (is (= 10 (get-in loaded [["Ingest Issues" "issues"] :cursor :cursor/value])))
        (is (= 5 (get-in loaded [["Ingest Merge Requests" "merge-requests"]
                                 :cursor :cursor/value])))))))

(defn- ^{:stratum 1} persisted-cursor
  "Save `cursor-value` as a timestamp watermark, then load it back.

   Both I/O results are asserted here. `save-cursors` and
   `load-cursors` report failure as a `schema/failure` map rather than
   throwing, so an unchecked write or read error would reach the
   caller as a nil cursor and fail some later assertion with a
   misleading `(not (string? nil))` instead of naming the real
   problem."
  [cursor-value]
  (let [path   (tmp-pipeline-path)
        saved  (sut/save-cursors
                logger path
                {(random-uuid) {:stage/name          "Ingest Issues"
                                :stage/connector-ref (random-uuid)
                                :stage/schema-name   "issues"
                                :cursor {:cursor/type  :timestamp-watermark
                                         :cursor/value cursor-value}
                                :cursor/updated-at (Instant/now)}})
        loaded (sut/load-cursors logger path)]
    (is (:success? saved) (str "save-cursors failed: " (:error saved)))
    (is (:success? loaded) (str "load-cursors failed: " (:error loaded)))
    (-> loaded :cursors (get ["Ingest Issues" "issues"]) :cursor)))

;; ---------------------------------------------------------------------------
;; normalization
(deftest ^{:stratum 1} normalization-drops-incomplete-entries-test
  (testing "Entries without stage-name or schema-name are excluded"
    (let [path       (tmp-pipeline-path)
          good-id    (random-uuid)
          no-schema  (random-uuid)
          no-name    (random-uuid)
          cursor-map {good-id   {:stage/name        "Ingest PRs"
                                 :stage/schema-name "pulls"
                                 :cursor            {:cursor/type :offset :cursor/value 1}
                                 :cursor/updated-at (Instant/now)}
                      no-schema {:stage/name        "No-schema stage"
                                 :cursor            {:cursor/type :offset :cursor/value 2}
                                 :cursor/updated-at (Instant/now)}
                      no-name   {:stage/schema-name "orphan"
                                 :cursor            {:cursor/type :offset :cursor/value 3}
                                 :cursor/updated-at (Instant/now)}}]
      (sut/save-cursors logger path cursor-map)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 1 (count loaded)))
        (is (contains? loaded ["Ingest PRs" "pulls"]))))))

;; ---------------------------------------------------------------------------
;; idempotency — second save overwrites first
(deftest ^{:stratum 1} overwrite-test
  (testing "Saving again overwrites prior cursor with updated value"
    ;; Every identifier a run generates differs between run1 and run2 —
    ;; stage id and connector-ref are both fresh UUIDs each time. Only
    ;; the pack-declared stage name and schema name carry over, which is
    ;; why the store keys on those: anything else makes the second save
    ;; a second entry instead of an update, and the watermark never
    ;; advances.
    (let [path   (tmp-pipeline-path)
          stage  "Ingest Issues"
          schema "issues"
          run-of (fn [value]
                   {(random-uuid) {:stage/name          stage
                                   :stage/connector-ref (random-uuid)
                                   :stage/schema-name   schema
                                   :cursor {:cursor/type :offset :cursor/value value}
                                   :cursor/updated-at (Instant/now)}})]
      (sut/save-cursors logger path (run-of 10))
      (sut/save-cursors logger path (run-of 20))
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 1 (count loaded)))
        (is (= 20 (get-in loaded [[stage schema] :cursor :cursor/value])))))))

(deftest ^{:stratum 1} save-preserves-stages-absent-from-this-run-test
  (testing "a stage that produced no cursor this run keeps its previous one"
    ;; A source with nothing new returns no cursor at all, so its stage
    ;; is simply missing from the run's cursor map. Replacing the file
    ;; rather than merging would erase that stage's watermark and
    ;; re-ingest its whole history next run, while the stages that did
    ;; report kept advancing — a partial regression nothing would flag.
    (let [path (tmp-pipeline-path)]
      (sut/save-cursors logger path (merge (stage-cursor "Ingest PRs" 10)
                                           (stage-cursor "Ingest Issues" 20)))
      ;; Second run: only "Ingest PRs" had new records.
      (sut/save-cursors logger path (stage-cursor "Ingest PRs" 11))
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 2 (count loaded)))
        (is (= 11 (get-in loaded [["Ingest PRs" "Ingest PRs"] :cursor :cursor/value]))
            "the reporting stage advances")
        (is (= 20 (get-in loaded [["Ingest Issues" "Ingest Issues"] :cursor :cursor/value]))
            "the quiet stage holds its watermark")))))

(deftest ^{:stratum 1} save-with-no-cursors-leaves-the-file-alone-test
  (testing "a run where nothing reported a cursor does not clear the file"
    (let [path (tmp-pipeline-path)]
      (sut/save-cursors logger path (stage-cursor "Ingest PRs" 10))
      (let [result (sut/save-cursors logger path {})]
        (is (:success? result))
        (is (= 10 (get-in (:cursors result)
                          [["Ingest PRs" "Ingest PRs"] :cursor :cursor/value]))))
      (is (= 10 (get-in (:cursors (sut/load-cursors logger path))
                        [["Ingest PRs" "Ingest PRs"] :cursor :cursor/value]))))))

(deftest ^{:stratum 1} non-map-cursor-file-fails-loudly-test
  (testing "a file that does not hold a map is a failure, not a fresh start"
    ;; `edn/read-string` returns nil for an empty file, and a run killed
    ;; mid-write leaves exactly that. Reading it as "no cursors yet"
    ;; would restart every stage from scratch and still report success.
    (doseq [content ["" "nil" "[1 2 3]"]]
      (let [path (tmp-pipeline-path)
            file (io/file (.getParentFile (io/file path))
                          ".cursors" (.getName (io/file path)))]
        (io/make-parents file)
        (spit file content)
        (let [loaded (sut/load-cursors logger path)]
          (is (false? (:success? loaded))
              (str "content " (pr-str content) " should fail the load")))
        (let [saved (sut/save-cursors logger path
                                      (stage-cursor "Ingest PRs" 10))]
          (is (false? (:success? saved))
              (str "content " (pr-str content) " should fail the save")))))))

(deftest ^{:stratum 1} inst-tagged-cursor-file-loads-as-string-test
  (testing "a cursor file containing #inst still yields a filterable watermark"
    ;; `#inst` is the obvious literal for a human editing a cursor file by
    ;; hand, and EDN's reader turns it into a java.util.Date — which
    ;; parse-timestamp rejects. The read path normalizes, so the store's
    ;; guarantee holds regardless of who wrote the file.
    (let [path (tmp-pipeline-path)
          file (io/file (.getParentFile (io/file path))
                        ".cursors" (.getName (io/file path)))]
      (io/make-parents file)
      (spit file (pr-str {["Ingest Issues" "issues"]
                          {:stage/name        "Ingest Issues"
                           :stage/schema-name "issues"
                           :cursor {:cursor/type  :timestamp-watermark
                                    :cursor/value (Date/from
                                                   (Instant/parse watermark-iso))}}}))
      (let [loaded (sut/load-cursors logger path)
            cursor (get-in (:cursors loaded) [["Ingest Issues" "issues"] :cursor])]
        (is (:success? loaded) (str "load-cursors failed: " (:error loaded)))
        (is (= watermark-iso (:cursor/value cursor)))
        (is (false? (connector-http/after-cursor?
                     record-timestamp cursor {:updated_at "2024-01-14T00:00:00Z"})))
        (is (true? (connector-http/after-cursor?
                    record-timestamp cursor {:updated_at "2024-01-16T00:00:00Z"})))))))

;------------------------------------------------------------------------------ Layer 2

;; ---------------------------------------------------------------------------
;; instant normalization — a persisted watermark the consumer can honour
;;
;; `clojure.core/inst?` admits java.util.Date as readily as
;; java.time.Instant, and a Date has a print form (#inst) that survives
;; the EDN round-trip intact — so a Date-valued watermark used to reach
;; disk and come back unchanged with nothing throwing anywhere. The
;; damage was downstream and silent: connector-http's parse-timestamp
;; requires a string, returned nil for the Date, and after-cursor? fell
;; through to its no-watermark branch — admitting EVERY record and
;; re-ingesting the whole source on every run.
;;
;; Asserting the serialized shape alone would not have caught that. The
;; filtering assertions below are the ones that fail on the unfixed
;; store.
(deftest ^{:stratum 2} date-watermark-round-trip-test
  (testing "a java.util.Date watermark persists as an ISO-8601 string"
    (let [cursor (persisted-cursor (Date/from (Instant/parse watermark-iso)))]
      (is (string? (:cursor/value cursor)))
      (is (= watermark-iso (:cursor/value cursor)))))

  (testing "the loaded cursor actually filters — records at or before it are excluded"
    (let [cursor (persisted-cursor (Date/from (Instant/parse watermark-iso)))]
      (is (false? (connector-http/after-cursor?
                   record-timestamp cursor {:updated_at "2024-01-14T00:00:00Z"})))
      (is (false? (connector-http/after-cursor?
                   record-timestamp cursor {:updated_at watermark-iso})))
      (is (true? (connector-http/after-cursor?
                  record-timestamp cursor {:updated_at "2024-01-16T00:00:00Z"})))))

  (testing "an Instant watermark is indistinguishable once persisted"
    (is (= (:cursor/value (persisted-cursor (Instant/parse watermark-iso)))
           (:cursor/value (persisted-cursor (Date/from (Instant/parse watermark-iso))))))))
