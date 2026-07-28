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
   [ai.miniforge.supervisory-state.attention :as attention]
   [ai.miniforge.decision-envelope.interface :as env]
   [ai.miniforge.response.interface :as response]
   [ai.miniforge.supervisory-state.golden-fixtures :as golden-fixtures]
   [ai.miniforge.supervisory-state.schema :as schema]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [cognitect.transit :as transit]
   [malli.core :as m])
  (:import
   [java.io ByteArrayInputStream]
   [java.nio.file Files]))

;------------------------------------------------------------------------------ Layer 0

;------------------------------------------------------------------------------ Helpers
(defn- ^{:stratum 0} temp-dir []
  (.toFile (Files/createTempDirectory "golden-fixtures-test" (make-array java.nio.file.attribute.FileAttribute 0))))

(defn- ^{:stratum 0} read-transit [s]
  (transit/read
   (transit/reader (ByteArrayInputStream. (.getBytes ^String s "UTF-8")) :json)))

(defn- ^{:stratum 0} slurp-dir
  "Map of filename -> content for every file in `dir`. Empty map when
   `dir` is missing or not a directory (`.listFiles` returns nil there)
   so the drift-gate equality reports a clear diff instead of an NPE."
  [dir]
  (into {}
        (for [^java.io.File f (or (.listFiles (io/file dir)) [])]
          [(.getName f) (slurp f :encoding "UTF-8")])))

(def ^{:stratum 0} ^:private max-workspace-walk-hops
  "Upper bound on parent-directory hops when locating workspace.edn —
   deep enough for any worktree nesting in this repo, small enough to
   fail fast when the tests run outside the workspace entirely."
  8)

(def ^{:stratum 0} ^:private family->schema
  {:workflow-run schema/WorkflowRun
   :spec         schema/Spec
   :agent        schema/AgentSession
   :pr           schema/PrFleetEntry
   :policy-eval  schema/PolicyEvaluation
   :attention    schema/AttentionItem
   :task-node    schema/TaskNode
   :decision     schema/DecisionCard
   :intervention schema/InterventionRequest
   :gate-decision env/DecisionEnvelope})

(deftest ^{:stratum 0} validate-result-returns-anomaly-for-invalid-entity
  (testing "invalid golden fixture entities are represented as anomaly data"
    (let [result (@#'golden-fixtures/validate-result
                  :workflow-run
                  schema/WorkflowRun
                  {:not :valid})]
      (is (response/anomaly-map? result))
      (is (= :anomalies/incorrect (:anomaly/category result)))
      (is (= :workflow-run (:family result)))
      (is (some? (:errors result))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} find-workspace-root
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

;------------------------------------------------------------------------------ Tests
(deftest ^{:stratum 1} generator-covers-every-family-and-is-deterministic
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

(deftest ^{:stratum 1} fixtures-round-trip-with-version-and-schema-valid-entities
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

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} committed-fixtures-match-fresh-generation
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

(deftest ^{:stratum 2} emitted-attention-keys-are-corpus-exercised
  (testing "every key a runtime attention rule can attach appears in the
            committed golden attention fixture — additive drift fails HERE,
            on the producer, instead of silently dropping at the Rust seam
            (Ariadne 1e, T3 contradiction 2)"
    (let [wf-id (java.util.UUID/randomUUID)
          table {:policy-evals
                 {(java.util.UUID/randomUUID)
                  {:policy-eval/id (java.util.UUID/randomUUID)
                   :policy-eval/workflow-run-id wf-id
                   :policy-eval/passed? false
                   :policy-eval/gate-id :policy-review
                   :policy-eval/target-type :pr
                   :policy-eval/target-id ["golden/synthetic-repo" 7]
                   :policy-eval/violations
                   [{:violation/severity :high
                     :violation/rule-id :golden/forbidden
                     :violation/message "synthetic"}]}}
                 :agents {(java.util.UUID/randomUUID)
                          {:agent/id (java.util.UUID/randomUUID)
                           :agent/name "golden-agent"
                           :agent/status :blocked}}
                 :workflow-runs {} :prs {} :specs {}}
          derived (vals (attention/derive-items table))
          emitted-keys (into #{} (mapcat keys) derived)
          committed (io/file (find-workspace-root)
                             "contracts" "supervisory-entities" "golden"
                             "attention.transit.json")
          fixture-keys (-> (read-transit (slurp committed :encoding "UTF-8"))
                           (get :supervisory/entity)
                           keys
                           set)]
      (is (seq derived) "synthetic table must derive at least one item")
      (is (empty? (remove fixture-keys emitted-keys))
          (str "attention keys emitted at runtime but absent from the "
               "vendored corpus: " (pr-str (remove fixture-keys emitted-keys)))))))
