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
(ns ai.miniforge.workflow.checkpoint-store-test
  (:require
   [clojure.edn :as edn]
   [clojure.string :as str]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.workflow.checkpoint-store :as checkpoint-store]
   [ai.miniforge.workflow.checkpoint-store-paths :as checkpoint-paths]
   [ai.miniforge.workflow.context :as ctx]
   [ai.miniforge.workflow.interface :as workflow]
   [ai.miniforge.tenancy.interface :as tenancy]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} with-temp-checkpoint-root
  [f]
  (let [root (doto (io/file (System/getProperty "java.io.tmpdir")
                            (str "mf-checkpoint-test-" (random-uuid)))
               .mkdirs)]
    (try
      (f (.getAbsolutePath root))
      (finally
        (doseq [file (reverse (file-seq root))]
          (.delete ^java.io.File file))))))

(def ^{:stratum 0} ^:private instant-stamp
  "One instant, written twice — once as an Instant, once as the Date
   `clojure.core/inst?` admits just as readily — so the snapshot can be
   asserted to type them the same way on the way back out."
  (java.time.Instant/parse "2026-04-24T00:00:00.123Z"))

(deftest ^{:stratum 0} load-checkpoint-data-validates-at-interface-boundary-test
  (testing "invalid checkpoint payloads are rejected at the public interface"
    (with-redefs [checkpoint-store/load-checkpoint-data
                  (fn [_workflow-run-id _opts]
                    {:checkpoint/root "/tmp/checkpoints"
                     :manifest {:workflow/id (random-uuid)
                                :workflow/workflow-id :canonical-sdlc
                                :workflow/workflow-version "1.0.0"
                                :workflow/phases-completed [:plan]
                                :workflow/machine-snapshot-path "/tmp/checkpoints/run/machine-snapshot.edn"
                                :workflow/phase-checkpoints {:plan "/tmp/checkpoints/run/phases/plan.edn"}
                                :workflow/last-checkpoint-at "2026-04-24T00:00:00Z"}
                     :machine-snapshot {:execution/id (random-uuid)
                                        :execution/workflow-id :canonical-sdlc
                                        :execution/workflow-version "1.0.0"
                                        :execution/status :running
                                        :execution/fsm-state {:_state :running}
                                        :execution/response-chain {}
                                        :execution/errors []
                                        :execution/artifacts []
                                        :execution/metrics {}}
                     :phase-results {:plan :invalid-phase-result}})]
      (is (thrown-with-msg? clojure.lang.ExceptionInfo
                            #"Invalid checkpoint data"
                            (workflow/load-checkpoint-data (random-uuid) {}))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} persist-and-load-checkpoint-data-test
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [workflow {:workflow/id :test
                      :workflow/version "1.0.0"
                      :workflow/pipeline [{:phase :done}]}
            execution-ctx (-> (ctx/create-context workflow {:task "Test"}
                                                  {:checkpoint/root checkpoint-root})
                              (assoc-in [:execution/phase-results :done]
                                        {:status :completed
                                         :summary "done"})
                              (assoc :execution/dag-result
                                     {:paused? true
                                      :completed-task-ids [:task-a]
                                      :artifacts [{:artifact/id "art-1"}]
                                      :pause-reason :rate-limit})
                              (assoc :execution/current-phase :done
                                     :execution/started-at instant-stamp
                                     :execution/ended-at (java.util.Date/from
                                                          instant-stamp)
                                     :execution/output
                                     {:status :running
                                      :finished-at (java.time.Instant/now)}))
            _ (checkpoint-store/persist-execution-state! execution-ctx)
            checkpoint-data (checkpoint-store/load-checkpoint-data
                             (:execution/id execution-ctx)
                             {:checkpoint/root checkpoint-root})]
        (testing "loads machine snapshot and phase results"
          (is (= (:execution/id execution-ctx)
                 (get-in checkpoint-data [:machine-snapshot :execution/id])))
          (is (= {:status :completed
                  :summary "done"}
                 (get-in checkpoint-data [:phase-results :done])))
          (is (= {:paused? true
                  :completed-task-ids [:task-a]
                  :artifacts [{:artifact/id "art-1"}]
                  :pause-reason :rate-limit}
                 (get-in checkpoint-data [:machine-snapshot :execution/dag-result]))))
        (testing "serializes nested instant values into EDN-readable strings"
          (is (string? (get-in checkpoint-data
                               [:machine-snapshot :execution/started-at])))
          (is (string? (get-in checkpoint-data
                               [:machine-snapshot
                                :execution/output
                                :finished-at]))))
        (testing "a Date is typed the same as an Instant across a restore"
          ;; Pre-fix the walker matched java.time.Instant only, so a Date
          ;; kept its #inst print form and came back a Date — leaving
          ;; :execution/started-at a String and :execution/ended-at a Date
          ;; on the same restored snapshot, decided by which writer stamped
          ;; them.
          (is (string? (get-in checkpoint-data
                               [:machine-snapshot :execution/ended-at])))
          (is (= (get-in checkpoint-data [:machine-snapshot :execution/started-at])
                 (get-in checkpoint-data [:machine-snapshot :execution/ended-at]))))
        (testing "manifest tracks checkpointed phases"
          (is (= [:done]
                 (get-in checkpoint-data [:manifest :workflow/phases-completed]))))))))

(deftest ^{:stratum 1} persist-execution-state-validates-before-saving-test
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [workflow {:workflow/id :test
                      :workflow/version "1.0.0"
                      :workflow/pipeline [{:phase :done}]}
            invalid-ctx (-> (ctx/create-context workflow {:task "Test"}
                                                {:checkpoint/root checkpoint-root})
                            (assoc :execution/phase-results {:done :invalid-phase-result}))]
        (is (thrown-with-msg? clojure.lang.ExceptionInfo
                              #"Invalid checkpoint data"
                              (checkpoint-store/persist-execution-state! invalid-ctx)))))))

(deftest ^{:stratum 1} persist-execution-state-preserves-existing-phase-checkpoints-test
  (testing "a resumed partial pipeline does not shrink the checkpoint manifest"
    (with-temp-checkpoint-root
      (fn [checkpoint-root]
        (let [workflow-id (random-uuid)
              initial-workflow {:workflow/id :test
                                :workflow/version "1.0.0"
                                :workflow/pipeline [{:phase :explore}
                                                    {:phase :plan}
                                                    {:phase :implement}
                                                    {:phase :review}]}
              resumed-workflow {:workflow/id :test
                                :workflow/version "1.0.0"
                                :workflow/pipeline [{:phase :release}]}
              initial-ctx (-> (ctx/create-context initial-workflow {:task "Test"}
                                                  {:checkpoint/root checkpoint-root})
                              (assoc :execution/id workflow-id)
                              (assoc :execution/phase-results
                                     {:explore {:status :completed}
                                      :plan {:status :completed}
                                      :implement {:status :completed}
                                      :review {:status :completed}}))
              resumed-ctx (-> (ctx/create-context resumed-workflow {:task "Test"}
                                                  {:checkpoint/root checkpoint-root})
                              (assoc :execution/id workflow-id)
                              (assoc :execution/phase-results
                                     {:plan {:status :completed}
                                      :implement {:status :completed}
                                      :release {:status :failed}})
                              (assoc :execution/current-phase :release))]
          (checkpoint-store/persist-execution-state! initial-ctx)
          (checkpoint-store/persist-execution-state! resumed-ctx)
          (let [checkpoint-data (checkpoint-store/load-checkpoint-data
                                 workflow-id
                                 {:checkpoint/root checkpoint-root})]
            (is (= [:explore :plan :implement :review :release]
                   (get-in checkpoint-data [:manifest :workflow/phases-completed])))
            (is (= {:status :completed}
                   (get-in checkpoint-data [:phase-results :explore])))
            (is (= {:status :failed}
                   (get-in checkpoint-data [:phase-results :release])))))))))

(deftest ^{:stratum 1} persist-execution-state-preserves-phase-handoffs-test
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [handoff {:frame/kind :repair-request
                     :transition/from :review
                     :transition/to :implement}
            workflow {:workflow/id :test
                      :workflow/version "1.0.0"
                      :workflow/pipeline [{:phase :review}]}
            execution-ctx (-> (ctx/create-context workflow {:task "Test"}
                                                  {:checkpoint/root checkpoint-root})
                              (assoc :execution/phase-handoffs [handoff])
                              (assoc-in [:execution/phase-results :review]
                                        {:status :failed
                                         :phase/handoff handoff})
                              (assoc :execution/current-phase :review))
            _ (checkpoint-store/persist-execution-state! execution-ctx)
            checkpoint-data (checkpoint-store/load-checkpoint-data
                             (:execution/id execution-ctx)
                             {:checkpoint/root checkpoint-root})]
        (is (= [handoff]
               (get-in checkpoint-data
                       [:machine-snapshot :execution/phase-handoffs])))
        (is (= handoff
               (get-in checkpoint-data
                       [:phase-results :review :phase/handoff])))))))

(deftest ^{:stratum 1} inst-tagged-checkpoint-loads-as-string-test
  (testing "a checkpoint written before normalization restores as a string"
    ;; Checkpoints already on disk predate the write-side normalization, so
    ;; a Date in one of them was persisted as `#inst` — and EDN's reader
    ;; hands that back as a Date, which is the mixed-type restore this
    ;; change exists to stop. Written here by hand because the current
    ;; write path can no longer produce it.
    (with-temp-checkpoint-root
      (fn [checkpoint-root]
        (let [run-id (random-uuid)
              snapshot-path (checkpoint-paths/machine-snapshot-path
                             checkpoint-root run-id)]
          (io/make-parents snapshot-path)
          ;; :started-at as the old writer left it (#inst), :ended-at as the
          ;; new writer produces it — the mixed file an upgrade actually meets.
          (spit snapshot-path
                (pr-str {:execution/id run-id
                         :execution/started-at (java.util.Date/from instant-stamp)
                         :execution/ended-at (str instant-stamp)}))
          (let [snapshot (:machine-snapshot
                          (checkpoint-store/load-checkpoint-data
                           run-id {:checkpoint/root checkpoint-root}))]
            (is (string? (:execution/started-at snapshot)))
            (is (= (:execution/ended-at snapshot)
                   (:execution/started-at snapshot)))))))))

(deftest ^{:stratum 1} acting-identity-survives-a-checkpoint-test
  ;; `persisted-execution-keys` is an allowlist: a field absent from it
  ;; is dropped at checkpoint and gone on resume, silently. Identity is
  ;; the one field where that failure is unrecoverable after the fact,
  ;; so it gets a round trip through real disk rather than a unit
  ;; assertion on the key list.
  (with-temp-checkpoint-root
    (fn [checkpoint-root]
      (let [acting (tenancy/establish-acting
                    (tenancy/resolve-operator {:tenancy {:operator-name "chris"}}
                                              instant-stamp)
                    instant-stamp)
            workflow {:workflow/id :test
                      :workflow/version "1.0.0"
                      :workflow/pipeline [{:phase :done}]}
            ctx (ctx/create-context workflow {:task "Test"}
                                    {:checkpoint/root checkpoint-root
                                     :acting acting})
            _ (checkpoint-store/persist-execution-state! ctx)
            snapshot (:machine-snapshot
                      (checkpoint-store/load-checkpoint-data
                       (:execution/id ctx) {:checkpoint/root checkpoint-root}))]
        (testing "the boundary's identity reaches the context"
          (is (= acting (:execution/acting ctx))))
        (testing "and survives the durable snapshot INTACT"
          ;; Not just the ids. The snapshot puts every value through
          ;; `coerce/stringify-instants`, so a field typed `inst?` would
          ;; go in an Instant and come back a String, and the context
          ;; would fail its own validation on resume — leaving every
          ;; resumed run unowned. Asserting the whole map, and that a
          ;; consumer can still read it, is what catches that.
          (is (= acting (:execution/acting snapshot))
              "the acting context round-trips unchanged, field for field")
          (is (tenancy/valid-acting? (:execution/acting snapshot)))
          (is (= acting (tenancy/require-acting snapshot :execution/acting))
              "a consumer reading the restored snapshot gets the identity, not a refusal"))
        (testing "a resumed run keeps the authority it started with"
          ;; The trap this guards: `restore-context` merges the snapshot
          ;; first and then overrides `:execution/opts` from the CURRENT
          ;; invocation. Acting taken from opts would silently
          ;; reattribute the run to whoever resumed it.
          (let [someone-else (assoc acting
                                    :acting/principal-id (random-uuid)
                                    :acting/tenant-id (random-uuid))
                restored (ctx/restore-context workflow {:task "Test"} snapshot {}
                                              {:checkpoint/root checkpoint-root
                                               :acting someone-else})]
            (is (= (:acting/tenant-id acting)
                   (:acting/tenant-id (:execution/acting restored))))
            (is (= (:acting/principal-id acting)
                   (:acting/principal-id (:execution/acting restored)))
                "resuming must not reattribute the run to the resumer")
            (testing "and the context holds exactly ONE answer to who is acting"
              ;; Two sources of truth is the same defect wearing a
              ;; disguise: the resumer's identity in :execution/opts and
              ;; the original in :execution/acting, with a later reader
              ;; free to pick the wrong one and get a plausible wrong
              ;; owner instead of an error.
              (is (nil? (:acting (:execution/opts restored)))
                  "the resuming caller's :acting must not survive in opts")
              (is (nil? (:acting (:execution/opts ctx)))
                  "nor the starting caller's, in the fresh context"))))))))

(deftest ^{:stratum 1} gate-history-survives-phase-re-entry-test
  (testing "phase re-entry overwrites the phase checkpoint but every gate
            decision survives in the append-only gate history"
    (with-temp-checkpoint-root
      (fn [checkpoint-root]
        (let [workflow {:workflow/id :gate-history-test
                        :workflow/version "1.0.0"
                        :workflow/pipeline [{:phase :implement} {:phase :done}]}
              base (-> (ctx/create-context workflow {:task "Test"}
                                           {:checkpoint/root checkpoint-root})
                       (assoc :execution/current-phase :implement))
              denied (assoc-in base [:execution/phase-results :implement]
                               {:status :failed
                                :phase/decision-envelope {:envelope/decision :deny}
                                :phase/gate-failures [{:gate :stale-references
                                                       :errors [{:message "stale"
                                                                 ;; policy-pack violations carry Instants
                                                                 :timestamp (java.time.Instant/parse "2026-08-29T00:00:00Z")}]}]})
              allowed (assoc-in base [:execution/phase-results :implement]
                                {:status :completed
                                 :phase/decision-envelope {:envelope/decision :allow}})
              _ (checkpoint-store/persist-execution-state! denied)
              _ (checkpoint-store/persist-execution-state! allowed)
              run-dir (str checkpoint-root "/" (:execution/id base))
              history (->> (slurp (str run-dir "/" checkpoint-store/gate-history-filename))
                           str/split-lines
                           (mapv edn/read-string))
              phase-file (edn/read-string
                          (slurp (str run-dir "/phases/implement.edn")))]
          (is (= 2 (count history)) "both iterations recorded")
          (is (= [:deny :allow] (mapv :decision history)))
          (is (= :stale-references (-> history first :phase/gate-failures first :gate)))
          (is (= "2026-08-29T00:00:00Z"
                 (-> history first :phase/gate-failures first :errors first :timestamp))
              "Instants inside gate errors are normalized to strings so the history stays EDN-readable")
          (is (= :allow (get-in phase-file [:phase/result :phase/decision-envelope :envelope/decision]))
              "the phase checkpoint holds only the last iteration — the reason the history exists"))))))
