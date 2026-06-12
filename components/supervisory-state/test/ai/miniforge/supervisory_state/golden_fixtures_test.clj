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

(ns ai.miniforge.supervisory-state.golden-fixtures-test
  "The golden fixtures ARE the cross-language contract: these tests are
   the producer-side gate. They assert (a) the generator is total and
   deterministic, (b) every fixture round-trips through transit back to
   a schema-valid entity carrying the contract version, and (c) the
   COMMITTED fixtures under contracts/supervisory-entities/golden match
   a fresh generation — so any entity-shape change fails here with a
   regenerate instruction instead of silently breaking the Rust consumer."
  (:require
   [ai.miniforge.supervisory-state.golden-fixtures :as golden-fixtures]
   [ai.miniforge.supervisory-state.schema :as schema]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [cognitect.transit :as transit]
   [malli.core :as m])
  (:import
   [java.io ByteArrayInputStream]
   [java.nio.file Files]))

;------------------------------------------------------------------------------ Helpers

(defn- temp-dir []
  (.toFile (Files/createTempDirectory "golden-fixtures-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- read-transit [s]
  (transit/read
   (transit/reader (ByteArrayInputStream. (.getBytes ^String s "UTF-8")) :json)))

(defn- slurp-dir
  "Map of filename -> content for every file in `dir`. Empty map when
   `dir` is missing or not a directory (`.listFiles` returns nil there)
   so the drift-gate equality reports a clear diff instead of an NPE."
  [dir]
  (into {}
        (for [^java.io.File f (or (.listFiles (io/file dir)) [])]
          [(.getName f) (slurp f :encoding "UTF-8")])))

(def ^:private max-workspace-walk-hops
  "Upper bound on parent-directory hops when locating workspace.edn —
   deep enough for any worktree nesting in this repo, small enough to
   fail fast when the tests run outside the workspace entirely."
  8)

(defn- find-workspace-root
  "Walk up from user.dir until workspace.edn is found. Fails the calling
   test via exception when the workspace root cannot be located — a
   contract test that cannot find its contract must not pass quietly."
  []
  (loop [dir (io/file (System/getProperty "user.dir")) hops 0]
    (cond
      (.exists (io/file dir "workspace.edn")) dir
      (or (nil? (.getParentFile dir)) (>= hops max-workspace-walk-hops))
      (throw (ex-info "workspace.edn not found walking up from user.dir"
                      {:user-dir (System/getProperty "user.dir")}))
      :else (recur (.getParentFile dir) (inc hops)))))

(def ^:private family->schema
  {:workflow-run schema/WorkflowRun
   :spec         schema/Spec
   :agent        schema/AgentSession
   :pr           schema/PrFleetEntry
   :policy-eval  schema/PolicyEvaluation
   :attention    schema/AttentionItem
   :task-node    schema/TaskNode
   :decision     schema/DecisionCard
   :intervention schema/InterventionRequest})

;------------------------------------------------------------------------------ Tests

(deftest generator-covers-every-family-and-is-deterministic
  (let [dir-a (temp-dir)
        dir-b (temp-dir)
        summary (golden-fixtures/write-golden-fixtures! {:out-dir (.getPath dir-a)})]
    (golden-fixtures/write-golden-fixtures! {:out-dir (.getPath dir-b)})
    (testing "one fixture file per family plus the manifest"
      (is (= (set (keys family->schema)) (set (:families summary))))
      (is (= (into #{"manifest.edn"}
                   (map #(str (name %) ".transit.json") (:families summary)))
             (set (keys (slurp-dir dir-a))))))
    (testing "regeneration is byte-identical"
      (is (= (slurp-dir dir-a) (slurp-dir dir-b))))))

(deftest fixtures-round-trip-with-version-and-schema-valid-entities
  (let [dir (temp-dir)]
    (golden-fixtures/write-golden-fixtures! {:out-dir (.getPath dir)})
    (doseq [[family entity-schema] family->schema]
      (testing (str family)
        (let [event (read-transit (slurp (io/file dir (str (name family) ".transit.json"))
                                         :encoding "UTF-8"))]
          (is (= schema/schema-version (:supervisory/schema-version event))
              "every fixture event carries the contract version")
          (is (some? (:event/id event)))
          (is (some? (:event/timestamp event)))
          (is (m/validate entity-schema (:supervisory/entity event))
              (str "entity must round-trip schema-valid; explain: "
                   (pr-str (m/explain entity-schema (:supervisory/entity event))))))))))

(deftest committed-fixtures-match-fresh-generation
  (let [committed (io/file (find-workspace-root) "contracts" "supervisory-entities" "golden")
        dir (temp-dir)]
    (golden-fixtures/write-golden-fixtures! {:out-dir (.getPath dir)})
    (is (.exists committed)
        "contracts/supervisory-entities/golden must exist — run `bb fixtures:supervisory` and commit the output")
    (is (= (slurp-dir dir) (slurp-dir committed))
        (str "Committed golden fixtures differ from a fresh generation. "
             "The supervisory entity contract changed: bump schema-version if the "
             "change is breaking, run `bb fixtures:supervisory`, commit the diff, "
             "and re-vendor into miniforge-control."))))
