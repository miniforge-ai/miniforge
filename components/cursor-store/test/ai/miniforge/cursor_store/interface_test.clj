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
          conn-ref  :conn/gitlab
          schema    "issues"
          instant   (Instant/now)
          cursor-map {stage-id {:stage/id            stage-id
                                :stage/name          "Ingest Issues"
                                :stage/connector-ref conn-ref
                                :stage/schema-name   schema
                                :cursor              {:cursor/type  :offset
                                                      :cursor/value 42}
                                :cursor/updated-at   instant}}]
      (is (:success? (sut/save-cursors logger path cursor-map)))
      (let [result (sut/load-cursors logger path)]
        (is (:success? result))
        (let [loaded (get (:cursors result) [conn-ref schema])]
          (is (some? loaded))
          (is (= :offset (get-in loaded [:cursor :cursor/type])))
          (is (= 42 (get-in loaded [:cursor :cursor/value])))
          ;; :cursor/updated-at is stored as an ISO-8601 string (Instant not EDN-printable)
          (is (string? (:cursor/updated-at loaded)))
          (is (= (str instant) (:cursor/updated-at loaded))))))))

(deftest ^{:stratum 1} multi-stage-round-trip-test
  (testing "Multiple stages produce multiple entries keyed by connector/schema"
    (let [path  (tmp-pipeline-path)
          id-1  (random-uuid)
          id-2  (random-uuid)
          cursor-map {id-1 {:stage/connector-ref :conn/gitlab
                             :stage/schema-name   "issues"
                             :cursor              {:cursor/type :offset :cursor/value 10}
                             :cursor/updated-at   (Instant/now)}
                      id-2 {:stage/connector-ref :conn/gitlab
                             :stage/schema-name   "merge-requests"
                             :cursor              {:cursor/type :offset :cursor/value 5}
                             :cursor/updated-at   (Instant/now)}}]
      (sut/save-cursors logger path cursor-map)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 2 (count loaded)))
        (is (= 10 (get-in loaded [[:conn/gitlab "issues"] :cursor :cursor/value])))
        (is (= 5 (get-in loaded [[:conn/gitlab "merge-requests"] :cursor :cursor/value])))))))

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
                {(random-uuid) {:stage/connector-ref :conn/gitlab
                                :stage/schema-name   "issues"
                                :cursor {:cursor/type  :timestamp-watermark
                                         :cursor/value cursor-value}
                                :cursor/updated-at (Instant/now)}})
        loaded (sut/load-cursors logger path)]
    (is (:success? saved) (str "save-cursors failed: " (:error saved)))
    (is (:success? loaded) (str "load-cursors failed: " (:error loaded)))
    (-> loaded :cursors (get [:conn/gitlab "issues"]) :cursor)))

;; ---------------------------------------------------------------------------
;; normalization
(deftest ^{:stratum 1} normalization-drops-incomplete-entries-test
  (testing "Entries without connector-ref or schema-name are excluded"
    (let [path     (tmp-pipeline-path)
          good-id  (random-uuid)
          bad-id   (random-uuid)
          cursor-map {good-id {:stage/connector-ref :conn/github
                               :stage/schema-name   "pulls"
                               :cursor              {:cursor/type :offset :cursor/value 1}
                               :cursor/updated-at   (Instant/now)}
                      bad-id  {:stage/name        "No-ref stage"
                               :cursor            {:cursor/type :offset :cursor/value 2}
                               :cursor/updated-at (Instant/now)}}]
      (sut/save-cursors logger path cursor-map)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 1 (count loaded)))
        (is (contains? loaded [:conn/github "pulls"]))))))

;; ---------------------------------------------------------------------------
;; idempotency — second save overwrites first
(deftest ^{:stratum 1} overwrite-test
  (testing "Saving again overwrites prior cursor with updated value"
    (let [path     (tmp-pipeline-path)
          conn-ref :conn/gitlab
          schema   "issues"
          run1 {(random-uuid) {:stage/connector-ref conn-ref :stage/schema-name schema
                               :cursor {:cursor/type :offset :cursor/value 10}
                               :cursor/updated-at (Instant/now)}}
          run2 {(random-uuid) {:stage/connector-ref conn-ref :stage/schema-name schema
                               :cursor {:cursor/type :offset :cursor/value 20}
                               :cursor/updated-at (Instant/now)}}]
      (sut/save-cursors logger path run1)
      (sut/save-cursors logger path run2)
      (let [loaded (:cursors (sut/load-cursors logger path))]
        (is (= 20 (get-in loaded [[conn-ref schema] :cursor :cursor/value])))))))

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
      (spit file (pr-str {[:conn/gitlab "issues"]
                          {:stage/connector-ref :conn/gitlab
                           :stage/schema-name   "issues"
                           :cursor {:cursor/type  :timestamp-watermark
                                    :cursor/value (Date/from
                                                   (Instant/parse watermark-iso))}}}))
      (let [loaded (sut/load-cursors logger path)
            cursor (get-in (:cursors loaded) [[:conn/gitlab "issues"] :cursor])]
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
