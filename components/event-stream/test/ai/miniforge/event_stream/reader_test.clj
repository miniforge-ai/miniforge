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

(ns ai.miniforge.event-stream.reader-test
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [cheshire.core :as json]
   [ai.miniforge.event-stream.reader :as reader]))

(defn- with-temp-dir [body-fn]
  (let [dir (doto (io/file (System/getProperty "java.io.tmpdir")
                           (str "mf-reader-test-" (random-uuid)))
              .mkdirs)]
    (try
      (body-fn dir)
      (finally
        (doseq [^java.io.File f (reverse (file-seq dir))]
          (.delete f))))))

(defn- write-event! [^java.io.File dir filename event-map]
  (spit (io/file dir filename) (json/generate-string event-map)))

;------------------------------------------------------------------------------ Layer 0
;; strip-transit-prefix

(deftest strip-transit-prefix-keyword-keys-test
  (testing "string keys starting with ~: become keywords"
    (is (= {:event/type :workflow/started}
           (reader/strip-transit-prefix
             {"~:event/type" "~:workflow/started"})))))

(deftest strip-transit-prefix-nested-walk-test
  (testing "walks into nested maps and vectors"
    (is (= {:a [:b {:c :d}]}
           (reader/strip-transit-prefix
             {"~:a" ["~:b" {"~:c" "~:d"}]})))))

(deftest strip-transit-prefix-uuid-instant-test
  (testing "~u and ~t prefixes strip the 2-char prefix"
    (is (= "some-uuid" (reader/strip-transit-prefix "~usome-uuid")))
    (is (= "2026-04-21T00:00:00Z"
           (reader/strip-transit-prefix "~t2026-04-21T00:00:00Z")))))

(deftest strip-transit-prefix-non-tagged-strings-test
  (testing "plain strings and primitives pass through"
    (is (= "hello" (reader/strip-transit-prefix "hello")))
    (is (= 42 (reader/strip-transit-prefix 42)))
    (is (nil? (reader/strip-transit-prefix nil)))
    (is (true? (reader/strip-transit-prefix true)))))

;------------------------------------------------------------------------------ Layer 1
;; read-workflow-events — the resume-blocking bug

(deftest read-workflow-events-sorts-by-filename-test
  (with-temp-dir
    (fn [dir]
      (write-event! dir "20260420T000002Z-eventB.json"
                    {"~:event/type" "~:workflow/phase-started"
                     "~:workflow/phase" "~:plan"
                     "~:event/sequence-number" 4})
      (write-event! dir "20260420T000001Z-eventA.json"
                    {"~:event/type" "~:workflow/started"
                     "~:event/sequence-number" 0})
      (write-event! dir "20260420T000003Z-eventC.json"
                    {"~:event/type" "~:workflow/phase-completed"
                     "~:event/sequence-number" 6})
      (testing "events come back in filename (timestamp) order with stripped keys"
        (let [events (reader/read-workflow-events dir)]
          (is (= 3 (count events)))
          (is (= :workflow/started (:event/type (first events))))
          (is (= :workflow/phase-started (:event/type (second events))))
          (is (= :workflow/phase-completed (:event/type (last events))))
          (is (= :plan (:workflow/phase (second events)))))))))

(deftest read-workflow-events-ignores-non-json-test
  (with-temp-dir
    (fn [dir]
      (write-event! dir "good.json"
                    {"~:event/type" "~:workflow/started"})
      (spit (io/file dir "README.md") "not an event")
      (spit (io/file dir "garbage.txt") "{{{not json")
      (testing "only .json files are read"
        (let [events (reader/read-workflow-events dir)]
          (is (= 1 (count events))))))))

(deftest read-workflow-events-tolerates-corrupt-json-test
  (with-temp-dir
    (fn [dir]
      (write-event! dir "20260420T000001Z-good.json"
                    {"~:event/type" "~:workflow/started"})
      ;; Unparseable — one corrupt event must not kill the whole replay
      (spit (io/file dir "20260420T000002Z-bad.json") "{{{ not json")
      (write-event! dir "20260420T000003Z-ok.json"
                    {"~:event/type" "~:workflow/completed"})
      (testing "corrupt file is silently dropped, valid events still returned"
        (let [events (reader/read-workflow-events dir)]
          (is (= 2 (count events)))
          (is (= :workflow/started (:event/type (first events))))
          (is (= :workflow/completed (:event/type (last events)))))))))

(deftest read-workflow-events-missing-dir-returns-nil-test
  (testing "non-existent directory → nil (no throw)"
    (is (nil? (reader/read-workflow-events
                (io/file (System/getProperty "java.io.tmpdir")
                         (str "mf-reader-test-missing-" (random-uuid))))))))

(deftest read-workflow-events-by-id-composes-path-test
  (with-temp-dir
    (fn [base-dir]
      (let [wf-id (str (random-uuid))
            wf-dir (doto (io/file base-dir wf-id) .mkdirs)]
        (write-event! wf-dir "20260420T000001Z-e.json"
                      {"~:event/type" "~:workflow/started"
                       "~:workflow/id" (str "~u" wf-id)})
        (testing "composes base-dir + workflow-id and reads that subdir"
          (let [events (reader/read-workflow-events-by-id base-dir wf-id)]
            (is (= 1 (count events)))
            (is (= :workflow/started (:event/type (first events))))
            (is (= wf-id (:workflow/id (first events)))
                "~u UUID prefix is stripped")))))))
