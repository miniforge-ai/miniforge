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
(ns ai.miniforge.event-stream.layout-tail-test
  "The layout tail follows what the real file sink writes: single polls,
   and the started thread."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.layout-reader :as layout]
   [ai.miniforge.event-stream.layout-tail :as sut]
   [ai.miniforge.logging.interface :as log]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} window-ms (:window-ms sut/defaults))

(defn- ^{:stratum 0} temp-events-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-layout-tail-" (random-uuid)))
    .mkdirs))

(defn- ^{:stratum 0} file-sink-stream
  [events-dir]
  (es/create-event-stream
   {:config {:observability {:event-sinks [{:type :file :base-dir (str events-dir)}]}}}))

(defn- ^{:stratum 0} wf-event
  [event-type wf-id & {:as extra}]
  (merge {:event/type      event-type
          :event/id        (random-uuid)
          :event/timestamp (java.util.Date.)
          :workflow/id     wf-id}
         extra))

(defn- ^{:stratum 0} write-event-file!
  "Write `event` into the run's live directory as a run would at `ms`."
  [events-dir wf-id event ms]
  (let [dir  (doto (io/file events-dir "live" (str wf-id)) .mkdirs)
        at   (java.time.LocalDateTime/ofInstant (java.time.Instant/ofEpochMilli ms)
                                                java.time.ZoneOffset/UTC)
        name (str (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'") at)
                  "-" (random-uuid) ".json")
        f    (io/file dir name)]
    (spit f (es/serialize-event event))
    (.setLastModified f ms)
    (.setLastModified dir ms)))

(defn- ^{:stratum 0} archive!
  "Move the workflow's live directory to archived/, as the archive does."
  [events-dir wf-id]
  (let [to (io/file events-dir "archived" (str wf-id))]
    (.mkdirs (.getParentFile to))
    (.renameTo (es/workflow-dir (str events-dir) wf-id) to)))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} startup-reads-active-runs-in-full-and-skips-older-history
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        old-id     (random-uuid)
        active-id  (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)]
    (write-event-file! events-dir old-id (wf-event :workflow/started old-id)
                       (- (System/currentTimeMillis) (* 60 60 1000)))
    (es/publish! stream (wf-event :workflow/started active-id))
    (Thread/sleep 5)
    (es/publish! stream (wf-event :workflow/phase-started active-id))
    (let [t0 (System/currentTimeMillis)
          s1 (sut/poll-once! (sut/initial-state base t0 window-ms) base publish (+ t0 500))]
      (is (= [[active-id :workflow/started] [active-id :workflow/phase-started]]
             (mapv (juxt :workflow/id :event/type) @published))
          "a run active at startup is read from its first event; an old one is not")
      (testing "an old directory written to again joins at the next sweep, new files only"
        (reset! published [])
        (es/publish! stream (wf-event :workflow/resumed old-id))
        (sut/poll-once! s1 base publish (+ t0 500 sut/sweep-ms))
        (is (= [[old-id :workflow/resumed]]
               (mapv (juxt :workflow/id :event/type) @published)))))))

(deftest ^{:stratum 1} a-root-that-cannot-be-listed-is-listed-again-not-taken-as-empty
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        live       (io/file events-dir "live")
        wf-id      (random-uuid)
        published  (atom [])
        t0         (System/currentTimeMillis)]
    ;; A run started five minutes ago, so active, but older than the startup cutoff.
    (write-event-file! events-dir wf-id (wf-event :workflow/started wf-id) (- t0 300000))
    (let [s0 (try (.setReadable live false)
                  (is (nil? (.listFiles live)) "precondition: the test does not run as root")
                  (sut/initial-state base t0 window-ms)
                  (finally (.setReadable live true)))
          s1 (sut/initial-state base (+ t0 500) window-ms)
          s2 (sut/poll-once! s1 base #(swap! published conj %) (+ t0 1000))]
      (is (nil? s0) "startup is retried, not committed without its active runs")
      (is (= [:workflow/started] (mapv :event/type @published)) "then the run is read in full")
      (try (.setReadable live false)
           (is (= (:active s2) (:active (sut/relist s2 base [] (+ t0 sut/sweep-ms))))
               "a failed relist keeps the active set")
           (finally (.setReadable live true))))))

(deftest ^{:stratum 1} a-new-run-is-published-once-and-followed-into-archived
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        t0         (System/currentTimeMillis)
        s0         (sut/initial-state base t0 window-ms)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (spit (io/file (es/workflow-dir base wf-id) "manifest.json") "{\"workflow_id\":\"x\"}")
    (let [s1 (sut/poll-once! s0 base publish (+ t0 2000))
          s2 (sut/poll-once! s1 base publish (+ t0 2600))]
      (is (= [:workflow/started] (mapv :event/type @published))
          "a new workflow directory is found; a second poll repeats nothing; the manifest is not an event")
      (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
      (let [to (io/file events-dir "archived" (str wf-id))]
        (.mkdirs (.getParentFile to))
        (.renameTo (es/workflow-dir base wf-id) to))
      (sut/poll-once! s2 base publish (+ t0 4000))
      (is (= [:workflow/started :workflow/completed] (mapv :event/type @published))
          "after the move to archived/, only the event not yet seen is published"))))

(deftest ^{:stratum 1} a-completion-written-before-a-long-pause-is-read
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (let [t0 (System/currentTimeMillis)
          s1 (sut/poll-once! (sut/initial-state base t0 window-ms) base publish (+ t0 500))]
      (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
      ;; Polling paused past the window: the run leaves the active set.
      (let [s2 (sut/poll-once! s1 base publish (+ t0 window-ms sut/sweep-ms))]
        (is (not (contains? (:active s2) (str wf-id))))
        (is (= [:workflow/started :workflow/completed] (mapv :event/type @published))
            "read before the run leaves the active set")))))

(deftest ^{:stratum 1} later-events-wait-behind-a-file-that-fails-to-decode
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        read-event layout/read-event]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (Thread/sleep 2)
    (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
    (let [t0      (System/currentTimeMillis)
          started (first (filter #(= :workflow/started (:event/type (read-event %)))
                                 (layout/event-files (es/workflow-dir base wf-id))))
          s1      (with-redefs [layout/read-event #(when-not (= started %) (read-event %))]
                    (sut/poll-once! (sut/initial-state base t0 window-ms) base publish (+ t0 500)))]
      (is (= [] @published) "the completion waits behind the start's failed read")
      (sut/poll-once! s1 base publish (+ t0 1000))
      (is (= [:workflow/started :workflow/completed] (mapv :event/type @published))))))

(deftest ^{:stratum 1} a-corrupt-file-is-given-up-after-retries-with-a-logged-gap
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(do (swap! published conj %) (throw (ex-info "view down" {})))
        entries    (atom [])
        ;; The log write fails, as on a full disk: delivery must go on.
        logger     (log/create-logger {:output (fn [e] (swap! entries conj e)
                                                 (throw (ex-info "disk full" {})))})]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (Thread/sleep 2)
    (spit (io/file (es/workflow-dir base wf-id) (str (.format (java.time.format.DateTimeFormatter/ofPattern "yyyyMMdd'T'HHmmssSSS'Z'")
                                                              (java.time.LocalDateTime/now java.time.ZoneOffset/UTC))
                                                     "-corrupt.json"))
          "{not transit")
    (Thread/sleep 2)
    (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
    (let [t0    (System/currentTimeMillis)
          polls (iterate #(sut/poll-once! % base publish (+ t0 500))
                         (assoc (sut/initial-state base t0 window-ms) :logger logger))]
      (dorun (take layout/max-read-attempts polls))
      (is (= [:workflow/started] (mapv :event/type @published)) "held while it is retried")
      (dorun (take (inc layout/max-read-attempts) polls))
      (is (= [:workflow/started :workflow/completed] (mapv :event/type @published)))
      (is (= [:event-tail/gap] (map :log/event @entries))))))

(deftest ^{:stratum 1} tracking-keeps-a-window-of-names-and-finds-late-files-in-it
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        t0         (System/currentTimeMillis)
        write-at   #(write-event-file! events-dir wf-id (wf-event %2 wf-id) (- t0 %1))]
    (write-at 120000 :workflow/started)
    (write-at 90000 :phase/started)
    (write-at 0 :phase/completed)
    (let [s1 (sut/poll-once! (sut/initial-state base t0 window-ms) base publish (+ t0 500))]
      (is (= 3 (count @published)))
      (is (= 1 (count (get-in s1 [:tracked (str wf-id) :names])))
          "names more than a minute behind the newest give way to the watermark")
      (write-at 30000 :phase/started)
      (write-at 100000 :phase/started)
      (sut/poll-once! s1 base publish (+ t0 1000))
      (is (= 4 (count @published))
          "a late file within the minute is read; one below the watermark is given up"))))

(deftest ^{:stratum 1} a-directory-created-in-the-same-mtime-tick-as-the-listing-is-found
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        first-id   (random-uuid)
        second-id  (random-uuid)
        live-root  (io/file events-dir "live")
        published  (atom [])
        t0         (System/currentTimeMillis)]
    (es/publish! stream (wf-event :workflow/started first-id))
    (let [s0 (sut/initial-state base t0 window-ms)
          recorded (.lastModified live-root)]
      (es/publish! stream (wf-event :workflow/started second-id))
      ;; The new directory left the root's mtime as recorded, as a change
      ;; within the same mtime tick does.
      (.setLastModified live-root recorded)
      (sut/poll-once! s0 base #(swap! published conj %) (+ t0 1100))
      (is (= #{first-id second-id} (set (map :workflow/id @published)))))))

(deftest ^{:stratum 1} the-started-tail-hands-on-a-run-the-file-sink-writes
  (let [events-dir (temp-events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        seen       (atom [])
        stop!      (es/start-layout-tail! (str events-dir) #(swap! seen conj %) {:poll-ms 50})]
    (try
      (es/publish! stream (wf-event :workflow/started wf-id))
      (spit (io/file (es/workflow-dir (str events-dir) wf-id) ".es-1.transit.tmp")
            (es/serialize-event (wf-event :workflow/failed wf-id)))
      (loop [deadline (+ (System/currentTimeMillis) 10000)]
        (when (and (empty? @seen) (< (System/currentTimeMillis) deadline))
          (Thread/sleep 50)
          (recur deadline)))
      (Thread/sleep 500)
      (is (= [[wf-id :workflow/started]] (mapv (juxt :workflow/id :event/type) @seen))
          "once, with a UUID id; the temp file is not read")
      (finally (stop!)))))

(defn- ^{:stratum 1} archive-after-listing
  "An `event-files` stand-in that archives the run right after listing."
  [list-files events-dir wf-id]
  (fn [dir] (let [files (doall (list-files dir))] (archive! events-dir wf-id) files)))

(deftest ^{:stratum 1} a-run-archived-as-it-resumes-replays-nothing
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        dir        (es/workflow-dir base wf-id)
        published  (atom [])
        publish    #(swap! published conj %)
        list-files layout/event-files]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (let [t0 (System/currentTimeMillis)
          s1 (sut/poll-once! (sut/initial-state base t0 window-ms) base publish (+ t0 500))
          t1 (+ t0 window-ms sut/sweep-ms)
          s2 (sut/poll-once! s1 base publish t1)]
      ;; The resume's file records the start's millisecond, as a fast run can.
      (write-event-file! events-dir wf-id (wf-event :workflow/resumed wf-id)
                         (layout/file-ms (first (layout/event-files dir))))
      (.setLastModified dir (+ t1 1000))
      ;; Active again, but archived before this poll lists it: the listing finds nothing.
      (let [s3 (with-redefs [layout/event-files (fn [d] (archive! events-dir wf-id) (list-files d))]
                 (sut/poll-once! s2 base publish (+ t1 sut/sweep-ms)))]
        (sut/poll-once! s3 base publish (+ t1 sut/sweep-ms 2000))
        (is (= [:workflow/started :workflow/resumed] (mapv :event/type @published))
            "the event read before the idle gap is not read again")))))

(deftest ^{:stratum 1} a-run-whose-last-read-fails-is-read-again-before-it-retires
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        read-event layout/read-event]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (let [t0 (System/currentTimeMillis)
          s1 (sut/poll-once! (sut/initial-state base t0 window-ms) base publish (+ t0 500))
          t1 (+ t0 window-ms sut/sweep-ms)]
      (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
      ;; Polling paused past the window, and the leaving poll cannot list the run.
      (let [dir (es/workflow-dir base wf-id)
            s2  (try (.setReadable dir false)
                     (is (nil? (.listFiles dir)) "precondition: the test does not run as root")
                     (sut/poll-once! s1 base publish t1)
                     (finally (.setReadable dir true)))
            ;; Then the listing works, but the completion's read fails.
            s3  (with-redefs [layout/read-event #(when (= :workflow/started (:event/type (read-event %)))
                                                   (read-event %))]
                  (sut/poll-once! s2 base publish (+ t1 500)))
            s4  (sut/poll-once! s3 base publish (+ t1 1000))]
        (is (contains? (:retrying s2) (str wf-id)) "a failed listing is not a final read")
        (is (contains? (:retrying s3) (str wf-id)) "nor is a failed decode")
        (is (= [:workflow/started :workflow/completed] (mapv :event/type @published)))
        (is (empty? (:retrying s4)) "retired once its read succeeded")))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-run-moved-between-listing-and-decoding-loses-nothing
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        list-files layout/event-files
        t0         (System/currentTimeMillis)
        s0         (sut/initial-state base t0 window-ms)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (let [s1 (sut/poll-once! s0 base publish (+ t0 2000))]
      (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
      (let [s2 (with-redefs [layout/event-files (archive-after-listing list-files events-dir wf-id)]
                 (sut/poll-once! s1 base publish (+ t0 2500)))]
        (sut/poll-once! s2 base publish (+ t0 4000))
        (is (= [:workflow/started :workflow/completed] (mapv :event/type @published))
            "the completion, listed under live/ but read from archived/")))))

(deftest ^{:stratum 2} a-run-moved-during-its-first-listing-loses-nothing
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        list-files layout/event-files
        t0         (System/currentTimeMillis)
        s0         (sut/initial-state base t0 window-ms)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
    (let [s1 (with-redefs [layout/event-files (archive-after-listing list-files events-dir wf-id)]
               (sut/poll-once! s0 base #(swap! published conj %) (+ t0 2000)))]
      (sut/poll-once! s1 base #(swap! published conj %) (+ t0 4000))
      (is (= #{:workflow/started :workflow/completed} (set (map :event/type @published)))
          "first found under live/, read from archived/"))))
