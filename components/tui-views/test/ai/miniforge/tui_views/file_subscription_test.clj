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

(ns ai.miniforge.tui-views.file-subscription-test
  "Tests for file-based event subscription.
   Covers scanning, tracking, line reading, parse-and-dispatch,
   InterruptedException handling, and subscribe lifecycle."
  (:require
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.tui-views.file-subscription :as file-sub]))

(defn temp-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-file-sub-test-" (System/nanoTime)))
    .mkdirs))

(defn cleanup!
  [dir]
  (doseq [f (.listFiles dir)]
    (when (.isDirectory f) (cleanup! f))
    (.delete f))
  (.delete dir))

(defn write-event-file!
  [dir file-name events]
  (let [file (io/file dir file-name)]
    (with-open [w (io/writer file)]
      (doseq [event events]
        (.write w (pr-str event))
        (.write w "\n")))
    file))

;; ---------------------------------------------------------------------------- scan-event-files

(deftest scan-event-files-test
  (testing "finds .edn files sorted by name"
    (let [dir (temp-dir)]
      (try
        (spit (io/file dir "b.edn") "")
        (spit (io/file dir "a.edn") "")
        (spit (io/file dir "c.txt") "")
        (let [files (file-sub/scan-event-files dir)]
          (is (= 2 (count files)))
          (is (= "a.edn" (.getName (first files))))
          (is (= "b.edn" (.getName (second files)))))
        (finally (cleanup! dir)))))

  (testing "returns empty vec for non-existent dir"
    (is (= [] (file-sub/scan-event-files (io/file "/nonexistent/path")))))

  (testing "returns empty vec for nil"
    (is (= [] (file-sub/scan-event-files nil)))))

;; ---------------------------------------------------------------------------- read-new-lines

(deftest read-new-lines-test
  (testing "reads all lines from position 0"
    (let [dir (temp-dir)]
      (try
        (let [f (io/file dir "test.edn")
              _ (spit f "line1\nline2\nline3\n")
              pos (atom 0)
              lines (file-sub/read-new-lines f pos)]
          (is (= 3 (count lines)))
          (is (= "line1" (first lines)))
          (is (pos? @pos)))
        (finally (cleanup! dir)))))

  (testing "reads only new lines after position advances"
    (let [dir (temp-dir)]
      (try
        (let [f (io/file dir "test.edn")
              _ (spit f "line1\n")
              pos (atom 0)
              _ (file-sub/read-new-lines f pos)
              _ (spit f "line1\nline2\n")
              new-lines (file-sub/read-new-lines f pos)]
          (is (= 1 (count new-lines)))
          (is (= "line2" (first new-lines))))
        (finally (cleanup! dir)))))

  (testing "returns empty vec when file hasn't grown"
    (let [dir (temp-dir)]
      (try
        (let [f (io/file dir "test.edn")
              _ (spit f "data\n")
              pos (atom 0)
              _ (file-sub/read-new-lines f pos)
              lines (file-sub/read-new-lines f pos)]
          (is (empty? lines)))
        (finally (cleanup! dir))))))

;; ---------------------------------------------------------------------------- parse-and-dispatch!

(deftest parse-and-dispatch-test
  (testing "dispatches parsed events via dispatch-fn"
    (let [msgs (atom [])
          lines [(pr-str {:event/type :workflow/started
                          :workflow/id (random-uuid)
                          :workflow/spec {:name "Test"}})]]
      (file-sub/parse-and-dispatch! lines #(swap! msgs conj %))
      (is (= 1 (count @msgs)))))

  (testing "skips empty lines"
    (let [msgs (atom [])]
      (file-sub/parse-and-dispatch! ["" "  "] #(swap! msgs conj %))
      (is (= 0 (count @msgs)))))

  (testing "skips unparseable lines gracefully"
    (let [msgs (atom [])]
      (file-sub/parse-and-dispatch! ["not valid edn {{{"]
        #(swap! msgs conj %))
      (is (= 0 (count @msgs)))))

  (testing "tolerates #object tags via safe-read-edn"
    (let [msgs (atom [])
          line (str "{:event/type :workflow/failed, "
                    ":workflow/id #uuid \"aaaaaaaa-bbbb-cccc-dddd-eeeeeeeeeeee\", "
                    ":workflow/failure-reason \"NPE\", "
                    ":workflow/error-details {:anomaly {:anomaly/timestamp "
                    "#object[java.time.Instant 0x25d0a742 \"2026-03-08T18:14:28Z\"]}}}")
          _ (file-sub/parse-and-dispatch! [line] #(swap! msgs conj %))]
      (is (= 1 (count @msgs))))))

;; ---------------------------------------------------------------------------- track-file

(deftest track-file-hydrate-flag-test
  (testing "hydrate? false tracks the file from EOF without replaying history"
    (let [dir (temp-dir)
          file (write-event-file! dir "wf.edn"
                                  [{:event/type :workflow/started
                                    :workflow/id (random-uuid)
                                    :workflow/spec {:name "test"}}])
          tracked (atom {})
          dispatched (atom [])]
      (try
        (file-sub/track-file! tracked #(swap! dispatched conj %) file {:hydrate? false})
        (is (empty? @dispatched))
        (is (= (.length file)
               @(get-in @tracked [(.getAbsolutePath file) :position])))
        (finally
          (cleanup! dir)))))

  (testing "hydrate? true replays existing events immediately"
    (let [dir (temp-dir)
          file (write-event-file! dir "wf.edn"
                                  [{:event/type :workflow/started
                                    :workflow/id (random-uuid)
                                    :workflow/spec {:name "test"}}])
          tracked (atom {})
          dispatched (atom [])]
      (try
        (file-sub/track-file! tracked #(swap! dispatched conj %) file {:hydrate? true})
        (is (= 1 (count @dispatched)))
        (finally
          (cleanup! dir))))))

;; ---------------------------------------------------------------------------- poll-tracked-files!

(deftest poll-tracked-files-dispatches-new-lines-test
  (testing "poll-tracked-files! dispatches new lines added after tracking"
    (let [dir (temp-dir)]
      (try
        (let [f (io/file dir "wf.edn")
              _ (spit f "")
              tracked (atom {})
              msgs (atom [])]
          (file-sub/track-file! tracked #(swap! msgs conj %) f {:hydrate? false})
          ;; Append a new event
          (spit f (str (pr-str {:event/type :workflow/started
                                :workflow/id (random-uuid)
                                :workflow/spec {:name "Poll Test"}}) "\n")
                :append true)
          (file-sub/poll-tracked-files! tracked #(swap! msgs conj %))
          (is (= 1 (count @msgs))))
        (finally (cleanup! dir))))))

;; ---------------------------------------------------------------------------- scan-for-new-files!

(deftest scan-for-new-files-test
  (testing "discovers and tracks new .edn files"
    (let [dir (temp-dir)]
      (try
        (let [tracked (atom {})
              msgs (atom [])]
          ;; Write first file and track it
          (write-event-file! dir "a.edn"
            [{:event/type :workflow/started :workflow/id (random-uuid)
              :workflow/spec {:name "A"}}])
          (file-sub/scan-for-new-files! dir tracked #(swap! msgs conj %))
          (is (= 1 (count @tracked)))
          ;; Write second file
          (write-event-file! dir "b.edn"
            [{:event/type :workflow/started :workflow/id (random-uuid)
              :workflow/spec {:name "B"}}])
          (file-sub/scan-for-new-files! dir tracked #(swap! msgs conj %))
          (is (= 2 (count @tracked))))
        (finally (cleanup! dir))))))

;; ---------------------------------------------------------------------------- poll-loop InterruptedException

(deftest poll-loop-interrupt-stops-gracefully-test
  (testing "InterruptedException sets running? to false without stack trace"
    (let [running? (atom true)
          tracked (atom {})
          dir (temp-dir)
          dispatch-fn (fn [_])
          thread (Thread.
                   #(file-sub/poll-loop running? tracked dispatch-fn dir 50 500))]
      (try
        (.start thread)
        (Thread/sleep 100)
        (.interrupt thread)
        (.join thread 2000)
        (is (false? @running?) "running? should be false after interrupt")
        (is (not (.isAlive thread)) "thread should have stopped")
        (finally
          (cleanup! dir))))))

;; ---------------------------------------------------------------------------- subscribe-to-files! lifecycle

(deftest subscribe-to-files-returns-cleanup-fn-test
  (testing "subscribe-to-files! returns a callable cleanup function"
    (let [msgs (atom [])
          cleanup (file-sub/subscribe-to-files! #(swap! msgs conj %)
                    {:poll-ms 50 :scan-ms 200 :hydrate-existing? false})]
      (is (fn? cleanup))
      (cleanup)
      ;; Give thread time to stop
      (Thread/sleep 100))))
