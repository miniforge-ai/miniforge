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
(ns ai.miniforge.event-stream.live-retention-test
  "A Babashka run never archives, so its live/ directory stays forever.
   The cleanup archives it once it is finished and idle for the TTL."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.event-stream.sinks :as sinks]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest testing is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} temp-events-dir
  []
  (doto (io/file (System/getProperty "java.io.tmpdir") (str "live-retention-" (random-uuid)))
    .mkdirs))

(defn- ^{:stratum 0} age-tree!
  "Set the mtime of `dir` and everything in it to 30 days ago."
  [^java.io.File dir]
  (let [t (- (System/currentTimeMillis) (* 30 24 60 60 1000))]
    (doseq [^java.io.File f (reverse (file-seq dir))]
      (.setLastModified f t))))

(defn- ^{:stratum 0} write-run!
  "Write `event-types` for a new workflow with the real file sink under
   `events-dir`, as a Babashka run does (no manifest). Returns the id.
   The sink's own start-up cleanup gets an endless TTL, so only the
   test's cleanup calls act."
  [events-dir & event-types]
  (let [stream (es/create-event-stream
                {:config {:observability {:event-sinks [{:type :file
                                                         :base-dir (str events-dir)
                                                         :ttl-ms Long/MAX_VALUE}]}}})
        wf-id  (random-uuid)]
    (doseq [t event-types]
      (es/publish! stream (es/create-envelope stream t wf-id (name t))))
    wf-id))

(defn- ^{:stratum 0} live [events-dir wf-id] (io/file events-dir "live" (str wf-id)))

(defn- ^{:stratum 0} archived [events-dir wf-id] (io/file events-dir "archived" (str wf-id)))

(def ^{:stratum 0} ^:dynamic *held-id*
  "The workflow id this test's retention hold keeps, when bound."
  nil)

;------------------------------------------------------------------------------ Layer 1

(defmethod ^{:stratum 1} sinks/retention-hold ::checkpoint [_ workflow-id _]
  (= workflow-id *held-id*))

(deftest ^{:stratum 1} a-completed-idle-run-moves-to-archived
  (let [dir   (temp-events-dir)
        wf-id (write-run! dir :workflow/started :workflow/completed)]
    (age-tree! (live dir wf-id))
    (sinks/cleanup-stale-events! {:events-dir dir})
    (is (not (.exists (live dir wf-id))))
    (is (.isFile (io/file (archived dir wf-id) "archived.marker")))
    (is (= #{:workflow/started :workflow/completed}
           (set (map :event/type (es/read-workflow-events-by-id (str dir) wf-id))))
        "its events stay readable, now from archived/")))

(deftest ^{:stratum 1} an-interrupted-archive-is-finished-later
  (let [dir   (temp-events-dir)
        wf-id (write-run! dir :workflow/started :workflow/completed)]
    ;; A cleanup wrote the marker, then stopped before the rename.
    (spit (io/file (live dir wf-id) "archived.marker") "")
    (age-tree! (live dir wf-id))
    (sinks/cleanup-stale-events! {:events-dir dir})
    (is (not (.exists (live dir wf-id))))
    (is (.isFile (io/file (archived dir wf-id) "archived.marker")))))

(deftest ^{:stratum 1} live-runs-that-may-still-matter-stay
  (let [dir      (temp-events-dir)
        running  (write-run! dir :workflow/started :workflow/phase-started)
        recent   (write-run! dir :workflow/started :workflow/completed)
        failed   (write-run! dir :workflow/started :workflow/failed)
        resumed  (write-run! dir :workflow/started :workflow/failed
                             :workflow/started :workflow/phase-started)
        jvm-run  (write-run! dir :workflow/started :workflow/completed)]
    (spit (io/file (live dir jvm-run) "manifest.json") "{}")
    (run! #(age-tree! (live dir %)) [running failed resumed jvm-run])
    (sinks/cleanup-stale-events! {:events-dir dir})
    (testing "no terminal event: running, or crashed"
      (is (.isDirectory (live dir running))))
    (testing "completed, but written within the TTL"
      (is (.isDirectory (live dir recent))))
    (testing "failed: mf resume may still reuse its id"
      (is (.isDirectory (live dir failed))))
    (testing "failed, then resumed and running: the failure is not the newest"
      (is (.isDirectory (live dir resumed))))
    (testing "a manifest: the JVM runner's archive owns it"
      (is (.isDirectory (live dir jvm-run))))))

(deftest ^{:stratum 1} an-empty-idle-live-directory-goes
  (let [dir   (temp-events-dir)
        old   (doto (io/file dir "live" "old-empty") .mkdirs)
        fresh (doto (io/file dir "live" "fresh-empty") .mkdirs)]
    (age-tree! old)
    (sinks/cleanup-stale-events! {:events-dir dir})
    (is (not (.exists old)))
    (is (.isDirectory fresh) "a run may be about to write into it")))

(deftest ^{:stratum 1} an-archived-run-a-hold-keeps-is-not-pruned
  (let [dir   (temp-events-dir)
        wf-id (write-run! dir :workflow/started :workflow/completed)]
    (age-tree! (live dir wf-id))
    (sinks/cleanup-stale-events! {:events-dir dir})
    (age-tree! (archived dir wf-id))
    (binding [*held-id* (str wf-id)]
      (sinks/cleanup-stale-events! {:events-dir dir}))
    (is (.isDirectory (archived dir wf-id))
        "a run's checkpoint holds its events past the archived TTL")
    (sinks/cleanup-stale-events! {:events-dir dir})
    (is (not (.exists (archived dir wf-id))) "without the hold it goes")))
