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
(ns ai.miniforge.operator.consumer-test-support
  "Fixtures and stagers for the operator-event consumer tests.

   Extracted from consumer-test so the deftest namespace's own internal
   call graph stays within the stratified-design layer budget: the
   golden-corpus locators here form the deep chain, and living in a
   sibling namespace makes every call from a deftest a cross-namespace
   one that does not count toward the test file's depth. Internal to the
   operator component; deliberately not an interface."
  (:require
   [ai.miniforge.event-stream.interface :as es]
   [clojure.java.io :as io])
  (:import
   [java.nio.file Files]
   [java.nio.file.attribute FileAttribute]))

;------------------------------------------------------------------------------ Layer 0

(defn ^{:stratum 0} temp-events-dir
  ^java.io.File []
  (.toFile (Files/createTempDirectory "operator-consumer-test"
                                      (make-array FileAttribute 0))))

(defn ^{:stratum 0} stage-operator-file!
  "Copy fixture content into `{events-dir}/operator/{file-name}`."
  [events-dir file-name content]
  (let [target (io/file events-dir "operator" file-name)]
    (io/make-parents target)
    (spit target content :encoding "UTF-8")
    target))

(defn ^{:stratum 0} memory-stream
  "An event stream with no sinks: published events accumulate in the
   in-memory log only, so assertions read `es/get-events`."
  []
  (es/create-event-stream {:sinks []}))

(defn ^{:stratum 0} events-of-type
  [stream event-type]
  (filterv #(= event-type (:event/type %)) (es/get-events stream)))

(defn ^{:stratum 0} reject-every-request?
  [_event]
  false)

(def ^{:stratum 0} ^:const golden-fixture-count
  "Fixture scenarios the Rust generator commits (see manifest.edn).
   Pinned so a silently shrunken vendored corpus fails loudly."
  6)

;; `max-hops` inlined rather than a named const so this locator stays a
;; stratum-0 leaf: threading it through a const def would deepen the
;; golden-dir → stage-golden! chain past the file's three-layer budget.
;; 8 is deep enough for any worktree nesting, shallow enough to fail
;; fast when the tests run outside the workspace entirely.
(defn ^{:stratum 0} find-workspace-root
  []
  (loop [dir (io/file (System/getProperty "user.dir")) hops 0]
    (cond
      (.exists (io/file dir "workspace.edn")) dir
      (or (nil? (.getParentFile dir)) (>= hops 8))
      (throw (ex-info "workspace.edn not found walking up from user.dir"
                      {:user-dir (System/getProperty "user.dir")}))
      :else (recur (.getParentFile dir) (inc hops)))))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} golden-dir
  ^java.io.File []
  (io/file (find-workspace-root) "contracts" "operator-events" "golden"))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} stage-golden!
  [events-dir fixture-name]
  (stage-operator-file! events-dir fixture-name
                        (slurp (io/file (golden-dir) fixture-name)
                               :encoding "UTF-8")))
