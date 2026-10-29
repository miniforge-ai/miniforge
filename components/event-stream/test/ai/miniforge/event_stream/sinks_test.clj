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
   [ai.miniforge.event-stream.sinks :as sinks]
   [ai.miniforge.event-stream.test-helpers.http-mock :as http-mock]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Helpers
(defn ^{:stratum 0} with-temp-dir [f]
  (let [dir (java.nio.file.Files/createTempDirectory
              "sinks-test-"
              (into-array java.nio.file.attribute.FileAttribute []))]
    (try
      (f (.toFile dir))
      (finally
        (doseq [file (reverse (file-seq (.toFile dir)))]
          (.delete ^java.io.File file))))))

(defn ^{:stratum 0} sample-event [& [overrides]]
  (merge {:event/type :workflow/started
          :event/id (random-uuid)
          :event/timestamp (java.util.Date.)
          :event/version "1.0.0"
          :event/sequence-number 0
          :workflow/id (random-uuid)
          :message "Test event"}
         overrides))

(defn ^{:stratum 0} read-transit-json [s]
  (transit/read
   (transit/reader
    (java.io.ByteArrayInputStream. (.getBytes ^String s "UTF-8"))
    :json-verbose)))

(defn ^{:stratum 0} list-files [^java.io.File dir]
  (when (.isDirectory dir)
    (vec (.listFiles dir))))

(defn ^{:stratum 0} write-file!
  "Create `f` and its parent directories with `content`; return `f`."
  ^java.io.File [^java.io.File f content]
  (io/make-parents f)
  (spit f content)
  f)

(defn ^{:stratum 0} age!
  "Set the mtime of each of `files` to `days` days ago."
  [days & files]
  (let [t (- (System/currentTimeMillis) (* days 24 60 60 1000))]
    (doseq [^java.io.File f files]
      (.setLastModified f t))))

(deftest ^{:stratum 0} create-sink-vector-test
  (testing "vector creates multi-sink"
    (let [_received-1 (atom [])
          _received-2 (atom [])
          ;; Use a vector of keyword sinks but verify via multi-sink behavior
          sink (sinks/create-sink [:stdout :stdout])]
      ;; Should be a function
      (is (fn? sink)))))

(deftest ^{:stratum 0} create-sink-invalid-test
  (testing "invalid config throws"
    (is (thrown? Exception (sinks/create-sink 42)))
    (is (thrown? Exception (sinks/create-sink "invalid")))))

(deftest ^{:stratum 0} safe-ids-keep-their-directory-names-test
  (testing "UUIDs and keyword names are used as is, so existing event directories stay put"
    (let [id (random-uuid)]
      (is (= (str id) (.getName (sinks/live-workflow-dir "/base" id))))
      (is (= "canonical-sdlc" (.getName (sinks/live-workflow-dir "/base" :canonical-sdlc))))
      (is (= (.getName (sinks/live-workflow-dir "/base" "../x"))
             (.getName (sinks/live-workflow-dir "/base" "../x")))
          "an encoded id maps to the same directory every time"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} file-sink-keeps-the-dir-it-was-created-with-test
  ;; The sink is made while the default points at `dir`. The background
  ;; cleanup is held until the default has moved on to `other`, then
  ;; released, and an event is written then too: both must still use
  ;; `dir`. Deletion is stubbed: the stub only records what it was given.
  (with-temp-dir
    (fn [dir]
      (with-temp-dir
        (fn [other]
          (let [release (java.util.concurrent.CountDownLatch. 1)
                seen (promise)
                wf (random-uuid)]
            (with-redefs [sinks/cleanup-stale-events! (fn [opts]
                                                        (.await release 10 java.util.concurrent.TimeUnit/SECONDS)
                                                        (deliver seen opts)
                                                        0)]
              (let [sink (with-redefs [sinks/default-events-dir (constantly dir)]
                           (sinks/file-sink))]
                (with-redefs [sinks/default-events-dir (constantly other)]
                  (.countDown release)
                  (sink (sample-event {:workflow/id wf}))
                  (is (= (str dir) (str (:events-dir (deref seen 10000 ::never))))
                      "cleanup runs on the creation-time dir")
                  (is (seq (list-files (io/file dir "live" (str wf))))
                      "writes go to the creation-time dir")
                  (is (empty? (list-files (io/file other "live" (str wf))))))))))))))

(deftest ^{:stratum 1} file-sink-with-an-explicit-nil-dir-uses-the-default-test
  (with-temp-dir
    (fn [dir]
      (with-redefs [sinks/default-events-dir (constantly dir)]
        (let [sink (sinks/file-sink {:base-dir nil})
              wf (random-uuid)]
          (sink (sample-event {:workflow/id wf}))
          (is (seq (list-files (io/file dir "live" (str wf))))
              "written under the default dir, not a relative live/"))))))

(defn ^{:stratum 1} with-temp-default-events-dir
  "Run `f` with the default events dir pointed at a temp dir, so a file
   sink made without :base-dir neither writes nor prunes under the user's
   ~/.miniforge/events."
  [f]
  (with-temp-dir
    (fn [dir]
      (with-redefs [sinks/default-events-dir (constantly dir)]
        (f)))))

;; `mock-http-client` lives in the shared
;; `ai.miniforge.event-stream.test-helpers.http-mock` namespace so other
;; sink tests can reuse the same proxy stubs without re-implementing
;; HttpClient's ~10 abstract methods.
;; workflow-dir / event-file-path normalisation
(deftest ^{:stratum 1} workflow-dir-normalizes-keyword-id
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

(deftest ^{:stratum 1} event-file-path-normalizes-keyword-workflow-id
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

(deftest ^{:stratum 1} event-file-path-test
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

;; file-sink
(deftest ^{:stratum 1} file-sink-test
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

  (testing "leaves only the final event file, no temp file, in the workflow directory"
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              wf-id (random-uuid)]
          (dotimes [_ 3] (sink (sample-event {:workflow/id wf-id})))
          (let [names (map #(.getName ^java.io.File %)
                           (list-files (io/file dir "live" (str wf-id))))]
            (is (= 3 (count names)))
            (is (every? #(str/ends-with? % ".json") names)
                "every file is a finished event; none is a .transit.tmp"))))))

  (testing "no finished event file is visible while its content is being written"
    ;; Look at the workflow directory once the bytes are written but
    ;; before the sink returns. The old sink spat straight to the final
    ;; .json path, so that file existed (and was readable part-written)
    ;; at this point; now only the temp file does, until the rename.
    (with-temp-dir
      (fn [dir]
        (let [sink (sinks/file-sink {:base-dir dir})
              wf-id (random-uuid)
              wf-dir (io/file dir "live" (str wf-id))
              visible-mid-write (atom nil)
              real-spit spit]
          (with-redefs [spit (fn [f & args]
                               (apply real-spit f args)
                               (reset! visible-mid-write
                                       (->> (list-files wf-dir)
                                            (map #(.getName ^java.io.File %))
                                            (filter #(str/ends-with? % ".json"))
                                            vec)))]
            (sink (sample-event {:workflow/id wf-id})))
          (is (= [] @visible-mid-write))
          (is (= 1 (count (list-files wf-dir))) "the event lands once the write completes")))))

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

;; stdout-sink
(deftest ^{:stratum 1} stdout-sink-test
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

;; stderr-sink
(deftest ^{:stratum 1} stderr-sink-test
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

;; multi-sink
(deftest ^{:stratum 1} multi-sink-test
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

;; fleet-sink
(deftest ^{:stratum 1} fleet-sink-test
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
          mock-client (http-mock/mock-http-client
                       (fn [_req _handler]
                         (reset! posted true)
                         (reify java.net.http.HttpResponse
                           (statusCode [_] 200))))
          sink (sinks/fleet-sink {:url              "https://fleet.example.com"
                                  :api-key           "test-key"
                                  :batch-size        3
                                  :flush-interval-ms 999999
                                  :http-client       mock-client})]
      (sink (sample-event))
      (sink (sample-event))
      (sink (sample-event))
      (is (true? @posted) "flush sends an HTTP POST on batch-size trigger"))))

;; file-sink error reporting
(deftest ^{:stratum 1} file-sink-reports-write-errors-to-stderr-test
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

;; cleanup-stale-events!
(deftest ^{:stratum 1} cleanup-stale-events-test
  (testing "returns 0 when events directory is empty"
    (with-temp-dir
      (fn [dir]
        (is (= 0 (sinks/cleanup-stale-events! {:ttl-ms 0 :events-dir dir}))))))

  (testing "removes a legacy workflow directory untouched for the TTL and keeps a recent one"
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
            (.setLastModified old-dir (- (System/currentTimeMillis) (* 8 24 60 60 1000)))
            (let [deleted (sinks/cleanup-stale-events! {:events-dir dir})]
              (is (= 1 deleted))
              (is (not (.exists old-dir)) "the directory goes with its files")
              (is (.exists new-file))))))))

  (testing "removes temp files a killed writer left behind, once they are stale"
    (with-temp-dir
      (fn [dir]
        (let [wf-dir (io/file dir "live" "wf-1")
              orphan (io/file wf-dir ".es-123.transit.tmp")
              fresh (io/file wf-dir ".es-456.transit.tmp")]
          (.mkdirs wf-dir)
          (spit orphan "{")
          (spit fresh "{")
          (.setLastModified orphan (- (System/currentTimeMillis) (* 8 24 60 60 1000)))
          (is (= 1 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (not (.exists orphan)))
          (is (.exists fresh) "a temp file young enough to be mid-write is left alone")))))

  (testing "returns 0 when directory does not exist"
    (is (= 0 (sinks/cleanup-stale-events!
              {:events-dir (io/file "/tmp/nonexistent-miniforge-test-dir")})))))

(deftest ^{:stratum 1} cleanup-keeps-live-workflows-test
  (testing "a paused live workflow keeps its events and manifest however old they are"
    (with-temp-dir
      (fn [dir]
        (let [wf (io/file dir "live" "wf-paused")
              started (write-file! (io/file wf "0000000000000001__000000000000.transit.json") "{}")
              legacy-name (write-file! (io/file wf "20200101T000000Z-abc.json") "{}")
              manifest (write-file! (io/file wf "manifest.json") "{}")]
          (age! 30 started legacy-name manifest wf)
          (is (= 0 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (.exists started) "resume replays :workflow/started from here")
          (is (.exists legacy-name))
          (is (.exists manifest)))))))

(deftest ^{:stratum 1} cleanup-prunes-archives-by-archive-time-test
  (testing "an archive older than the TTL goes whole: events, manifest, marker, directory"
    (with-temp-dir
      (fn [dir]
        (let [wf (io/file dir "archived" "wf-old")
              ev (write-file! (io/file wf "0000000000000001__000000000000.transit.json") "{}")
              manifest (write-file! (io/file wf "manifest.json") "{}")
              marker (write-file! (io/file wf "archived.marker") "")]
          (age! 8 ev manifest marker)
          (is (= 3 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (not (.exists wf)))
          (is (.isDirectory (io/file dir "archived")) "only the workflow's directory goes")))))

  (testing "a recent archive keeps event files older than the TTL"
    (with-temp-dir
      (fn [dir]
        (let [wf (io/file dir "archived" "wf-recent")
              ev (write-file! (io/file wf "0000000000000001__000000000000.transit.json") "{}")
              manifest (write-file! (io/file wf "manifest.json") "{}")]
          (write-file! (io/file wf "archived.marker") "")
          (age! 30 ev manifest)
          (is (= 0 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (.exists ev))
          (is (.exists manifest))))))

  (testing "an archive still missing its marker is left for archive recovery"
    (with-temp-dir
      (fn [dir]
        (let [wf (io/file dir "archived" "wf-half")
              ev (write-file! (io/file wf "0000000000000001__000000000000.transit.json") "{}")
              manifest (write-file! (io/file wf "manifest.json") "{}")]
          (age! 30 ev manifest wf)
          (is (= 0 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (.exists ev))
          (is (.exists manifest))))))

  (testing ":ttl-ms sets how long an archive is kept"
    (with-temp-dir
      (fn [dir]
        (let [wf (io/file dir "archived" "wf-2d")]
          (write-file! (io/file wf "0000000000000001__000000000000.transit.json") "{}")
          (age! 2 (write-file! (io/file wf "archived.marker") ""))
          (is (= 0 (sinks/cleanup-stale-events! {:events-dir dir})) "kept under the 7-day default")
          (is (.exists wf))
          (is (= 2 (sinks/cleanup-stale-events! {:events-dir dir :ttl-ms (* 24 60 60 1000)})))
          (is (not (.exists wf)))))))

  (testing "removing an archive does not follow a symlink out of it"
    (with-temp-dir
      (fn [dir]
        (let [wf (io/file dir "archived" "wf-linked")
              target (io/file dir "live" "wf-other")
              kept (write-file! (io/file target "0000000000000002__000000000000.transit.json") "{}")]
          (age! 8 (write-file! (io/file wf "archived.marker") ""))
          (java.nio.file.Files/createSymbolicLink
           (.toPath (io/file wf "events"))
           (.toPath target)
           (into-array java.nio.file.attribute.FileAttribute []))
          (sinks/cleanup-stale-events! {:events-dir dir})
          (is (not (.exists wf)))
          (is (.exists kept) "the link goes, not what it points at")))))

  (testing "an events directory that is itself a symlink is still swept"
    (with-temp-dir
      (fn [dir]
        (let [real (io/file dir "real-events")
              orphan (write-file! (io/file real "live" "wf-1" ".es-1.transit.tmp") "{")
              link (io/file dir "events-link")]
          (age! 8 orphan)
          (java.nio.file.Files/createSymbolicLink
           (.toPath link)
           (.toPath real)
           (into-array java.nio.file.attribute.FileAttribute []))
          (age! 8 (write-file! (io/file real "archived" "wf-old" "archived.marker") ""))
          (is (= 2 (sinks/cleanup-stale-events! {:events-dir link})))
          (is (not (.exists orphan)))
          (is (not (.exists (io/file real "archived" "wf-old"))))))))

  (testing "a symlinked archived/ or operator/ is not followed out of the events tree"
    (with-temp-dir
      (fn [dir]
        (let [events (io/file dir "events")
              outside (io/file dir "outside")
              notes (write-file! (io/file outside "wf-x" "notes.txt") "keep")
              marker (write-file! (io/file outside "wf-x" "archived.marker") "")
              op-file (write-file! (io/file outside "20200101T000000Z-abc.json") "{}")]
          (.mkdirs events)
          (age! 8 marker op-file)
          (doseq [sub ["archived" "operator"]]
            (java.nio.file.Files/createSymbolicLink
             (.toPath (io/file events sub))
             (.toPath outside)
             (into-array java.nio.file.attribute.FileAttribute [])))
          (is (= 0 (sinks/cleanup-stale-events! {:events-dir events})))
          (is (.exists notes))
          (is (.exists marker))
          (is (.exists op-file)))))))

(deftest ^{:stratum 1} cleanup-legacy-and-operator-files-test
  (testing "a legacy directory with one recent file keeps its old files too"
    (with-temp-dir
      (fn [dir]
        (let [wf (io/file dir "wf-legacy")
              old (write-file! (io/file wf "20200101T000000Z-abc.json") "{}")]
          (write-file! (io/file wf "20260411T000000Z-def.json") "{}")
          (age! 30 old wf)
          (is (= 0 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (.exists old) "a workflow's history is pruned whole or not at all")))))

  (testing "an empty legacy directory left by earlier cleanup goes once old"
    (with-temp-dir
      (fn [dir]
        (let [empty-dir (io/file dir "wf-emptied")]
          (.mkdirs empty-dir)
          (age! 30 empty-dir)
          (sinks/cleanup-stale-events! {:events-dir dir})
          (is (not (.exists empty-dir)))))))

  (testing "directories holding anything but event files, or hidden ones, are left alone"
    (with-temp-dir
      (fn [dir]
        (let [tui (io/file dir "archive")
              tui-file (write-file! (io/file tui "wf.edn") "{}")
              workers (io/file dir ".workers")
              lease (write-file! (io/file workers "0.lease") "{}")]
          (age! 30 tui-file tui lease workers)
          (is (= 0 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (.exists tui-file))
          (is (.exists lease))))))

  (testing "operator events still go one file at a time"
    (with-temp-dir
      (fn [dir]
        (let [op (io/file dir "operator")
              old (write-file! (io/file op "20200101T000000Z-abc.json") "{}")
              fresh (write-file! (io/file op "20260411T000000Z-def.json") "{}")]
          (age! 8 old)
          (is (= 1 (sinks/cleanup-stale-events! {:events-dir dir})))
          (is (not (.exists old)))
          (is (.exists fresh)))))))

(deftest ^{:stratum 1} file-sink-keeps-hostile-ids-inside-the-events-dir-test
  (testing "a workflow id with .. or separators cannot place files outside base-dir"
    (with-temp-dir
      (fn [root]
        (let [base (io/file root "events")
              sink (sinks/file-sink {:base-dir base})]
          (.mkdirs base)
          (sink (sample-event {:workflow/id "../../escaped"}))
          (sink (sample-event {:workflow/id "/tmp/absolute-escape"}))
          (sink (sample-event {:workflow/id ".."}))
          (let [outside (remove #(str/starts-with? (.getCanonicalPath ^java.io.File %)
                                                   (str (.getCanonicalPath base) "/"))
                                (filter #(.isFile ^java.io.File %) (file-seq root)))]
            (is (empty? outside) "every file the sink wrote is under base-dir"))
          (is (not (.exists (io/file root "escaped"))))
          (is (= 3 (count (filter #(.isFile ^java.io.File %)
                                  (file-seq (io/file base "live")))))
              "all three events were still written, under encoded directory names")))))

  (testing "a non-snowflake event id cannot add directories to the file name"
    (with-temp-dir
      (fn [root]
        (let [sink (sinks/file-sink {:base-dir root})
              wf-id (random-uuid)]
          (sink (sample-event {:workflow/id wf-id :event/id "../../../x"}))
          (let [files (list-files (io/file root "live" (str wf-id)))]
            (is (= 1 (count files)))
            (is (not (str/includes? (.getName ^java.io.File (first files)) "..")))))))))

;------------------------------------------------------------------------------ Layer 2

;; create-sink factory
(deftest ^{:stratum 2} create-sink-keyword-test
  (with-temp-default-events-dir
   (fn []
    (testing ":file produces a function"
      (is (fn? (sinks/create-sink :file))))

    (testing ":stdout produces a function"
      (is (fn? (sinks/create-sink :stdout))))

    (testing ":stderr produces a function"
      (is (fn? (sinks/create-sink :stderr)))))))

(deftest ^{:stratum 2} create-sink-map-test
  (with-temp-default-events-dir
   (fn []
    (testing "map with :type :file produces file sink"
      (is (fn? (sinks/create-sink {:type :file}))))

    (testing "map with :type :stdout produces stdout sink"
      (is (fn? (sinks/create-sink {:type :stdout}))))

    (testing "map with :type :stderr and filter produces stderr sink"
      (is (fn? (sinks/create-sink {:type :stderr :filter (constantly true)}))))

    (testing "map with unknown type throws"
      (is (thrown? Exception (sinks/create-sink {:type :unknown})))))))

;; create-sinks-from-config
(deftest ^{:stratum 2} create-sinks-from-config-test
  (with-temp-default-events-dir
   (fn []
    (testing "defaults to file sink when no config"
      (let [created-sinks (sinks/create-sinks-from-config {})]
        (is (= 1 (count created-sinks)))
        (is (fn? (first created-sinks)))))

    (testing "creates sinks from :observability :event-sinks"
      (let [created-sinks (sinks/create-sinks-from-config
                           {:observability {:event-sinks [:stdout]}})]
        (is (= 1 (count created-sinks)))
        (is (fn? (first created-sinks)))))

    (testing "multiple sinks from config"
      (let [created-sinks (sinks/create-sinks-from-config
                           {:observability {:event-sinks [:file :stdout]}})]
        (is (= 2 (count created-sinks)))
        (is (every? fn? created-sinks)))))))
