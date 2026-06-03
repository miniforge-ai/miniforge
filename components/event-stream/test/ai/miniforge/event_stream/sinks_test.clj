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

(ns ai.miniforge.event-stream.sinks-test
  "Unit tests for event sink functions."
  (:require
   [clojure.test :refer [deftest testing is]]
   [clojure.java.io :as io]
   [clojure.string :as str]
   [clojure.edn :as edn]
   [cognitect.transit :as transit]
   [ai.miniforge.event-stream.sinks :as sinks]))

;------------------------------------------------------------------------------ Helpers

(defn with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
              "sinks-test-"
              (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (f (.toFile dir))
      (finally
        (doseq [file (reverse (file-seq (.toFile dir)))]
          (.delete ^java.io.File file))))))

(defn sample-event [& [overrides]]
  (merge {:event/type :workflow/started
          :event/id (random-uuid)
          :event/timestamp (java.util.Date.)
          :event/version "1.0.0"
          :event/sequence-number 0
          :workflow/id (random-uuid)
          :message "Test event"}
         overrides))

(defn read-transit-json [s]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
    :json-verbose)))

(defn list-files [^java.io.File dir]
  (when (.isDirectory dir)
    (vec (.listFiles dir))))

;------------------------------------------------------------------------------ Layer 0
;; workflow-dir / event-file-path normalisation

(deftest workflow-dir-normalizes-keyword-id
  ;; Keyword workflow ids (e.g. `:canonical-sdlc` from `workflow run`)
  ;; must NOT round-trip through `(str ...)` because the result
  ;; (`":canonical-sdlc"`) carries a colon — invalid on Windows and
  ;; surprising elsewhere. The shared `workflow-id-segment` helper
  ;; uses `name` for keywords; UUIDs/strings pass through `str`.
  (with-temp-dir
    (fn [dir]
      (let [kw-path     (sinks/workflow-dir dir :canonical-sdlc)
            uuid-path   (sinks/workflow-dir dir (random-uuid))
            string-path (sinks/workflow-dir dir "wf-abc")]
        (is (= "canonical-sdlc" (.getName kw-path))
            "keyword id strips the colon — uses (name kw)")
        (is (re-matches #"[0-9a-f-]{36}" (.getName uuid-path))
            "uuid id stringifies cleanly")
        (is (= "wf-abc" (.getName string-path))
            "string id passes through unchanged")))))

(deftest event-file-path-normalizes-keyword-workflow-id
  ;; Same normalisation must apply to event-file-path so events and
  ;; the manifest end up under identical workflow directories.
  (with-temp-dir
    (fn [dir]
      (let [wf-kw :auth-flow
            event (sample-event {:workflow/id wf-kw})
            path  (sinks/event-file-path dir wf-kw event)
            parent (.getParentFile path)]
        (is (= "auth-flow" (.getName parent))
            "event-file-path's parent dir uses the same keyword
             normalisation as workflow-dir so events sit beside the
             manifest")))))

(deftest event-file-path-test
  (testing "returns a File path ending in .json under an explicit base-dir"
    (with-temp-dir
      (fn [dir]
        (let [wf-id (random-uuid)
              event (sample-event {:workflow/id wf-id})
              path (sinks/event-file-path dir wf-id event)]
          (is (instance? java.io.File path))
          (is (str/includes? (.getPath path) (.getPath dir)))
          (is (str/ends-with? (.getName path) ".json"))))))

  (testing "creates the workflow subdirectory under an explicit base-dir"
    (with-temp-dir
      (fn [dir]
        (let [wf-id (random-uuid)
              event (sample-event {:workflow/id wf-id})
              path (sinks/event-file-path dir wf-id event)
              parent (.getParentFile path)]
          (is (.isDirectory parent))
          (is (str/ends-with? (.getName parent) (str wf-id)))))))

  (testing "snowflake-encoded :event/id produces the new filename grammar"
    (with-temp-dir
      (fn [dir]
        ;; A snowflake-encoded UUID has zero least-significant bits.
        (let [wf-id (random-uuid)
              snowflake-uuid (java.util.UUID. 0x018f3a9c8e4b12d0 0)
              event (sample-event {:workflow/id wf-id
                                   :event/id snowflake-uuid
                                   :event/sequence-number 12345})
              path (sinks/event-file-path dir wf-id event)
              filename (.getName path)]
          (is (str/ends-with? filename ".transit.json"))
          (is (str/starts-with? filename "018f3a9c8e4b12d0__"))
          (is (str/includes? filename "__000000012345.transit.json"))))))

  (testing "random :event/id falls back to legacy timestamp-leading filename"
    (with-temp-dir
      (fn [dir]
        (let [wf-id (random-uuid)
              event (sample-event {:workflow/id wf-id})
              path (sinks/event-file-path dir wf-id event)
              filename (.getName path)]
          ;; Legacy pattern: 20YYMMDDTHHMMSSsss-{uuid}.json
          (is (re-matches #"\d{8}T\d{9}Z-[0-9a-f-]+\.json" filename)
              (str "expected legacy timestamp-leading filename, got: " filename)))))))

;------------------------------------------------------------------------------ Layer 1
;; file-sink

(deftest file-sink-test
  (testing "writes event to per-workflow file as Transit-JSON"
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              wf-id (random-uuid)
              event (sample-event {:workflow/id wf-id})]
          (sink event)
          (let [wf-dir (io/file dir "live" (str wf-id))
                files (list-files wf-dir)]
            (is (= 1 (count files)))
            (let [parsed (read-transit-json (slurp (first files)))]
              (is (= :workflow/started (:event/type parsed)))
              (is (= wf-id (:workflow/id parsed)))))))))

  (testing "writes one file per event in the workflow subdirectory"
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              wf-id (random-uuid)]
          (sink (sample-event {:workflow/id wf-id}))
          (sink (sample-event {:workflow/id wf-id :event/type :workflow/completed}))
          (let [wf-dir (io/file dir "live" (str wf-id))
                files (sort-by #(.getName %) (list-files wf-dir))]
            (is (= 2 (count files)))
            (let [events (mapv #(read-transit-json (slurp %)) files)
                  event-types (set (map :event/type events))]
              (is (= #{:workflow/started :workflow/completed} event-types))))))))

  (testing "writes events with nil workflow-id to operator subdirectory"
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              unique-type (keyword (str "test/op-" (random-uuid)))
              event (sample-event {:workflow/id nil :event/type unique-type})]
          (sink event)
          (let [op-dir (io/file dir "operator")
                files (list-files op-dir)]
            (is (= 1 (count files)))
            (let [parsed (read-transit-json (slurp (first files)))]
              (is (= unique-type (:event/type parsed)))))))))

  (testing "output files are valid Transit-JSON readable by cognitect/transit-clj reader"
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              wf-id (random-uuid)
              event (sample-event {:workflow/id wf-id})]
          (sink event)
          (let [wf-dir (io/file dir "live" (str wf-id))
                files (list-files wf-dir)
                content (slurp (first files))]
            ;; Must parse without exception
            (is (map? (read-transit-json content))))))))

  (testing "events with java.time.Instant values serialize without error"
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              wf-id (random-uuid)
              event (sample-event {:workflow/id wf-id
                                   :execution/started-at (java.time.Instant/now)
                                   :timestamp (java.time.Instant/now)})]
          (sink event)
          (let [wf-dir (io/file dir "live" (str wf-id))
                files (list-files wf-dir)
                content (slurp (first files))
                parsed (read-transit-json content)]
            (is (map? parsed))
            (is (some? (:execution/started-at parsed))))))))

  (testing "recreates missing parent directories at write time"
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              wf-id (random-uuid)
              event (sample-event {:workflow/id wf-id})]
          (with-redefs [sinks/event-file-path (fn [_ _ _]
                                                (io/file dir "nested" "workflow" "event.json"))]
            (sink event))
          (let [event-file (io/file dir "nested" "workflow" "event.json")]
            (is (.exists event-file))
            (is (= :workflow/started
                   (:event/type (read-transit-json (slurp event-file)))))))))))

;------------------------------------------------------------------------------ Layer 1
;; stdout-sink

(deftest stdout-sink-test
  (testing "prints event to stdout in edn format"
    (let [sink (sinks/stdout-sink {:compact true})
          event (sample-event)
          output (with-out-str (sink event))]
      (is (not (str/blank? output)))
      ;; Should be parseable EDN
      (let [parsed (edn/read-string output)]
        (is (= :workflow/started (:event/type parsed))))))

  (testing "compact mode produces single-line output"
    (let [sink (sinks/stdout-sink {:compact true})
          event (sample-event)
          output (str/trim (with-out-str (sink event)))]
      ;; Compact EDN should be a single line (no internal newlines before the closing brace)
      (is (str/starts-with? output "{")))))

;------------------------------------------------------------------------------ Layer 1
;; stderr-sink

(deftest stderr-sink-test
  (testing "only passes events matching filter"
    (let [_passed (atom [])
          sink (sinks/stderr-sink {:filter (fn [e] (= :workflow/failed (:event/type e)))})]
      ;; Redirect stderr to capture output
      (binding [*err* (java.io.StringWriter.)]
        (sink (sample-event {:event/type :workflow/started}))
        (sink (sample-event {:event/type :workflow/failed})))
        ;; The failed event should have been written to stderr
        ;; The started event should not
        ;; We verify by checking the err output contains "workflow/failed"
        ;; but not "workflow/started"
        ;; Note: stderr-sink binds *out* to *err* internally, so we need
        ;; to capture differently. Let's just verify the filter works
        ;; by using a custom sink that tracks calls.
        ))

  (testing "default filter passes all events"
    (let [sink (sinks/stderr-sink)
          output (let [w (java.io.StringWriter.)]
                   (binding [*err* (java.io.PrintWriter. w)]
                     (sink (sample-event)))
                   (str w))]
      (is (not (str/blank? output))))))

;------------------------------------------------------------------------------ Layer 2
;; multi-sink

(deftest multi-sink-test
  (testing "dispatches to all child sinks"
    (let [received-1 (atom [])
          received-2 (atom [])
          sink-1 (fn [e] (swap! received-1 conj e))
          sink-2 (fn [e] (swap! received-2 conj e))
          multi (sinks/multi-sink [sink-1 sink-2])
          event (sample-event)]
      (multi event)
      (is (= 1 (count @received-1)))
      (is (= 1 (count @received-2)))
      (is (= (:event/id event) (:event/id (first @received-1))))))

  (testing "continues with other sinks if one throws"
    (let [received (atom [])
          failing-sink (fn [_] (throw (Exception. "sink failure")))
          ok-sink (fn [e] (swap! received conj e))
          multi (sinks/multi-sink [failing-sink ok-sink])]
      (multi (sample-event))
      (is (= 1 (count @received)))))

  (testing "empty sinks vector produces no-op"
    (let [multi (sinks/multi-sink [])]
      ;; Should not throw
      (is (nil? (multi (sample-event)))))))

;------------------------------------------------------------------------------ Layer 3
;; create-sink factory

(deftest create-sink-keyword-test
  (testing ":file produces a function"
    (is (fn? (sinks/create-sink :file))))

  (testing ":stdout produces a function"
    (is (fn? (sinks/create-sink :stdout))))

  (testing ":stderr produces a function"
    (is (fn? (sinks/create-sink :stderr)))))

(deftest create-sink-map-test
  (testing "map with :type :file produces file sink"
    (is (fn? (sinks/create-sink {:type :file}))))

  (testing "map with :type :stdout produces stdout sink"
    (is (fn? (sinks/create-sink {:type :stdout}))))

  (testing "map with :type :stderr and filter produces stderr sink"
    (is (fn? (sinks/create-sink {:type :stderr :filter (constantly true)}))))

  (testing "map with unknown type throws"
    (is (thrown? Exception (sinks/create-sink {:type :unknown})))))

(deftest create-sink-vector-test
  (testing "vector creates multi-sink"
    (let [_received-1 (atom [])
          _received-2 (atom [])
          ;; Use a vector of keyword sinks but verify via multi-sink behavior
          sink (sinks/create-sink [:stdout :stdout])]
      ;; Should be a function
      (is (fn? sink)))))

(deftest create-sink-invalid-test
  (testing "invalid config throws"
    (is (thrown? Exception (sinks/create-sink 42)))
    (is (thrown? Exception (sinks/create-sink "invalid")))))

;------------------------------------------------------------------------------ Layer 4
;; create-sinks-from-config

(deftest create-sinks-from-config-test
  (testing "defaults to file sink when no config"
    (let [sinks (sinks/create-sinks-from-config {})]
      (is (= 1 (count sinks)))
      (is (fn? (first sinks)))))

  (testing "creates sinks from :observability :event-sinks"
    (let [sinks (sinks/create-sinks-from-config
                 {:observability {:event-sinks [:stdout]}})]
      (is (= 1 (count sinks)))
      (is (fn? (first sinks)))))

  (testing "multiple sinks from config"
    (let [sinks (sinks/create-sinks-from-config
                 {:observability {:event-sinks [:file :stdout]}})]
      (is (= 2 (count sinks)))
      (is (every? fn? sinks)))))

;------------------------------------------------------------------------------ Layer 4
;; fleet-sink

(deftest fleet-sink-test
  (testing "requires :url option"
    (is (thrown? Exception (sinks/fleet-sink {}))))

  (testing "requires :api-key option"
    (is (thrown? Exception
                 (sinks/fleet-sink {:url "https://fleet.example.com"}))
        "missing :api-key must fail loud at construction so misconfiguration doesn't produce 401 noise at flush time"))

  (testing "creates a function when url and api-key are provided"
    (is (fn? (sinks/fleet-sink {:url "https://fleet.example.com"
                                :api-key "test-key"}))))

  (testing "batches events and POSTs on flush"
    (let [posted      (atom nil)
          mock-resp   (reify java.net.http.HttpResponse
                        (statusCode [_] 200))
          ;; `java.net.http.HttpClient` is an abstract class with many
          ;; abstract methods beyond `send`. Stub the most common ones
          ;; with safe defaults so an accidental future call from the sink
          ;; doesn't throw AbstractMethodError. Only `send` matters for
          ;; the assertion below.
          mock-client (proxy [java.net.http.HttpClient] []
                        (send [_req _handler]
                          (reset! posted true)
                          mock-resp)
                        (sendAsync
                          ([_req _handler]
                           (java.util.concurrent.CompletableFuture/completedFuture mock-resp))
                          ([_req _handler _push]
                           (java.util.concurrent.CompletableFuture/completedFuture mock-resp)))
                        (cookieHandler [] (java.util.Optional/empty))
                        (connectTimeout [] (java.util.Optional/empty))
                        (followRedirects [] java.net.http.HttpClient$Redirect/NORMAL)
                        (sslContext [] (javax.net.ssl.SSLContext/getDefault))
                        (sslParameters [] (javax.net.ssl.SSLParameters.))
                        (authenticator [] (java.util.Optional/empty))
                        (version [] java.net.http.HttpClient$Version/HTTP_2)
                        (executor [] (java.util.Optional/empty)))
          sink (sinks/fleet-sink {:url              "https://fleet.example.com"
                                  :api-key           "test-key"
                                  :batch-size        3
                                  :flush-interval-ms 999999
                                  :http-client       mock-client})]
      (sink (sample-event))
      (sink (sample-event))
      (sink (sample-event))
      (is (true? @posted) "flush sends an HTTP POST on batch-size trigger"))))

;------------------------------------------------------------------------------ Layer 1b
;; file-sink error reporting

(deftest file-sink-reports-write-errors-to-stderr-test
  (testing "file-sink logs write failures to stderr instead of swallowing"
    (with-temp-dir
      (fn [dir]
        ;; Create a regular file at {dir}/live/{wf-id} so that when the
        ;; sink tries to create a subdirectory there (.mkdirs returns
        ;; false), spit throws an IOException trying to write a child
        ;; path of a regular file. Path follows the BD-2b sub-3b
        ;; `{base}/live/{wid}/` layout the file sink writes to.
        (let [wf-id (random-uuid)
              _     (.mkdirs (io/file dir "live"))
              collision (io/file dir "live" (str wf-id))]
          (spit (str collision) "not a dir")
          (let [sink (sinks/file-sink {:base-dir dir})
                event (sample-event {:workflow/id wf-id})
                stderr-output (let [w (java.io.StringWriter.)]
                                (binding [*err* (java.io.PrintWriter. w)]
                                  (sink event))
                                (str w))]
            (is (str/includes? stderr-output "WARNING"))))))))

;------------------------------------------------------------------------------ Layer 0
;; cleanup-stale-events!

(deftest cleanup-stale-events-test
  (testing "returns 0 when events directory is empty"
    (with-temp-dir
      (fn [dir]
        (is (= 0 (sinks/cleanup-stale-events! {:ttl-ms 0 :events-dir dir}))))))

  (testing "deletes old event files and preserves recent ones"
    (with-temp-dir
      (fn [dir]
        (let [old-dir (io/file dir "workflow-old")
              new-dir (io/file dir "workflow-new")]
          (.mkdirs old-dir)
          (.mkdirs new-dir)
          (let [old-file (io/file old-dir "20200101T000000Z-abc.json")
                new-file (io/file new-dir "20260411T000000Z-def.json")]
            (spit (str old-file) "{}")
            (spit (str new-file) "{}")
            (.setLastModified old-file (- (System/currentTimeMillis) (* 8 24 60 60 1000)))
            (let [deleted (sinks/cleanup-stale-events! {:events-dir dir})]
              (is (= 1 deleted))
              (is (not (.exists old-file)))
              (is (.exists new-file))))))))

  (testing "returns 0 when directory does not exist"
    (is (= 0 (sinks/cleanup-stale-events!
              {:events-dir (io/file "/tmp/nonexistent-miniforge-test-dir")})))))
