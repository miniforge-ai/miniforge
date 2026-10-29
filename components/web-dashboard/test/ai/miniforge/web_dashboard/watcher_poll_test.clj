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
(ns ai.miniforge.web-dashboard.watcher-poll-test
  "Single polls of the watcher against directories the real file sink writes."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.web-dashboard.event-files :as event-files]
   [ai.miniforge.web-dashboard.watcher :as sut]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-events-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir")
                 (str "miniforge-watcher-poll-" (random-uuid)))
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

(defn- ^{:stratum 0} age-dir!
  "Set the mtime of the workflow's live directory and its files an hour back."
  [events-dir wf-id]
  (let [dir (es/workflow-dir (str events-dir) wf-id)
        hour-ago (- (System/currentTimeMillis) (* 60 60 1000))]
    (doseq [f (.listFiles dir)] (.setLastModified f hour-ago))
    (.setLastModified dir hour-ago)))

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
    (es/publish! stream (wf-event :workflow/started old-id))
    (age-dir! events-dir old-id)
    (es/publish! stream (wf-event :workflow/started active-id))
    (Thread/sleep 5)
    (es/publish! stream (wf-event :workflow/phase-started active-id))
    (let [t0 (System/currentTimeMillis)
          s1 (sut/poll-once! (sut/initial-state base t0) base publish (+ t0 500))]
      (is (= [[active-id :workflow/started] [active-id :workflow/phase-started]]
             (mapv (juxt :workflow/id :event/type) @published))
          "a run active at startup is read from its first event; an old one is not")
      (testing "an old directory written to again joins at the next sweep, new files only"
        (reset! published [])
        (es/publish! stream (wf-event :workflow/resumed old-id))
        (sut/poll-once! s1 base publish (+ t0 500 sut/sweep-ms))
        (is (= [[old-id :workflow/resumed]]
               (mapv (juxt :workflow/id :event/type) @published)))))))

(deftest ^{:stratum 1} a-new-run-is-published-once-and-followed-into-archived
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        t0         (System/currentTimeMillis)
        s0         (sut/initial-state base t0)]
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

(deftest ^{:stratum 1} a-run-idle-past-the-window-repeats-nothing-when-written-again
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        dir        (es/workflow-dir base wf-id)
        published  (atom [])
        publish    #(swap! published conj %)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (let [t0 (System/currentTimeMillis)
          s1 (sut/poll-once! (sut/initial-state base t0) base publish (+ t0 500))
          seen (get-in s1 [:seen (str wf-id)])
          ;; Nothing written for the whole window: the sweep drops the directory.
          t1 (+ t0 sut/active-window-ms 1000)
          s2 (sut/poll-once! s1 base publish t1)]
      (is (not (contains? (:active s2) (str wf-id))))
      (es/publish! stream (wf-event :phase/started wf-id))
      ;; The new file (and so the directory) carries a time after t1.
      (doseq [f (cons dir (remove #(contains? seen (.getName ^java.io.File %)) (.listFiles dir)))]
        (.setLastModified ^java.io.File f (+ t1 1000)))
      (sut/poll-once! s2 base publish (+ t1 sut/sweep-ms))
      (is (= [:workflow/started :phase/started] (mapv :event/type @published))
          "the event seen before the idle gap is not published again"))))

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
    (let [s0 (sut/initial-state base t0)
          recorded (.lastModified live-root)]
      (es/publish! stream (wf-event :workflow/started second-id))
      ;; The new directory left the root's mtime as recorded, as a change
      ;; within the same mtime tick does.
      (.setLastModified live-root recorded)
      (sut/poll-once! s0 base #(swap! published conj %) (+ t0 1100))
      (is (= #{first-id second-id} (set (map :workflow/id @published)))))))

(defn- ^{:stratum 1} archive-after-listing
  "An `event-files` stand-in that archives the run right after listing."
  [list-files events-dir wf-id]
  (fn [dir] (let [files (doall (list-files dir))] (archive! events-dir wf-id) files)))

(deftest ^{:stratum 1} a-run-moved-before-the-poll-lists-it-repeats-nothing
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        t0         (System/currentTimeMillis)
        s0         (sut/initial-state base t0)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (let [s1 (sut/poll-once! s0 base publish (+ t0 2000))]
      (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
      (archive! events-dir wf-id)
      ;; Too soon to relist: this poll lists the live path, now gone.
      (-> (sut/poll-once! s1 base publish (+ t0 2500))
          (sut/poll-once! base publish (+ t0 4000)))
      (is (= [:workflow/started :workflow/completed] (mapv :event/type @published))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} a-run-moved-between-listing-and-decoding-loses-nothing
  (let [events-dir (temp-events-dir)
        base       (str events-dir)
        stream     (file-sink-stream events-dir)
        wf-id      (random-uuid)
        published  (atom [])
        publish    #(swap! published conj %)
        list-files event-files/event-files
        t0         (System/currentTimeMillis)
        s0         (sut/initial-state base t0)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (let [s1 (sut/poll-once! s0 base publish (+ t0 2000))]
      (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
      (let [s2 (with-redefs [event-files/event-files (archive-after-listing list-files events-dir wf-id)]
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
        list-files event-files/event-files
        t0         (System/currentTimeMillis)
        s0         (sut/initial-state base t0)]
    (es/publish! stream (wf-event :workflow/started wf-id))
    (es/publish! stream (wf-event :workflow/completed wf-id :workflow/status :success))
    (let [s1 (with-redefs [event-files/event-files (archive-after-listing list-files events-dir wf-id)]
               (sut/poll-once! s0 base #(swap! published conj %) (+ t0 2000)))]
      (sut/poll-once! s1 base #(swap! published conj %) (+ t0 4000))
      (is (= #{:workflow/started :workflow/completed} (set (map :event/type @published)))
          "first found under live/, read from archived/"))))
