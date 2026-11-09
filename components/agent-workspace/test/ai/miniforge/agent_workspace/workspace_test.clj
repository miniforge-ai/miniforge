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
(ns ai.miniforge.agent-workspace.workspace-test
  (:require
   [ai.miniforge.agent-workspace.fixtures :as fx]
   [ai.miniforge.agent-workspace.interface :as workspace]
   [ai.miniforge.anomaly.interface :as anomaly]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} clone [w repo-ids]
  (workspace/clone! (:wc w) (:acting w) (:lease w) (:grant w) (fx/request repo-ids)))

(defn- ^{:stratum 0} deliver-all [w ws] (workspace/deliver! (:wc w) (:acting w) (:grant w) ws))

(defn- ^{:stratum 0} port-down [_proposal] (anomaly/anomaly :unavailable "pr service down" {}))

(deftest ^{:stratum 0} test-the-pr-grant-must-be-a-pr-grant-for-this-principal
  (let [w (fx/world)
        provision (fx/issue (:acting w) :effect/provision (:grant/scope (:grant w)) (:now w))]
    (is (= :anomalies.agent-workspace/grant-wrong-effect
           (anomaly/subtype (workspace/clone! (:wc w) (:acting w) (:lease w) provision (fx/request fx/allowed)))))))

(deftest ^{:stratum 0} test-ids-and-refs-that-could-escape-or-act-as-options-are-invalid
  (let [w (fx/world)]
    (doseq [req [(assoc (fx/request ["acme-example/frontend"]) :workspace/repos [{:repo/id "../etc" :repo/base-ref "main"}])
                 (assoc (fx/request ["acme-example/frontend"]) :workspace/repos [{:repo/id "acme-example/frontend" :repo/base-ref "--upload-pack=x"}])
                 (assoc (fx/request ["acme-example/frontend"]) :workspace/root "relative/root")]]
      (is (= :invalid-input (:anomaly/type (workspace/clone! (:wc w) (:acting w) (:lease w) (:grant w) req)))))
    (is (empty? (fx/git-commands w)))))

(deftest ^{:stratum 0} test-a-workspace-scope-that-is-not-a-set-allows-nothing
  (let [w (fx/world)
        listed (fx/issue (:acting w) :effect/pr-create (update (:grant/scope (:grant w)) :workspace/repos vec) (:now w))]
    (is (= :anomalies.agent-workspace/repo-not-allowed
           (anomaly/subtype (workspace/clone! (:wc w) (:acting w) (:lease w) listed (fx/request fx/allowed)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} test-another-tenants-lease-is-refused-before-any-record-is-read
  (let [w (fx/world)
        ws (clone w (take 1 fx/allowed))
        _ (deliver-all w ws)
        stranger (assoc (:acting w) :acting/tenant-id (random-uuid))]
    (is (= :anomalies.agent-workspace/forged-tenant (anomaly/subtype (workspace/deliver! (:wc w) stranger (:grant w) ws))))
    (is (= :anomalies.agent-workspace/forged-tenant (anomaly/subtype (workspace/clone! (:wc w) stranger (:lease w) (:grant w) (fx/request fx/allowed)))))))

(deftest ^{:stratum 1} test-three-repositories-one-branch-and-one-pr-each
  (let [w (fx/world)
        ws (clone w fx/allowed)
        delivered (deliver-all w ws)]
    (testing "given three allowed repositories → three checkouts on one task branch"
      (is (= fx/allowed (mapv :repo/id (:workspace/checkouts ws))))
      (is (= 1 (count (set (map :repo/task-branch (:workspace/checkouts ws))))))
      (is (every? #(= fx/base-sha (:repo/base-sha %)) (:workspace/checkouts ws))))
    (testing "given delivery → three succeeded PR effects, each pushed and opened once"
      (is (= [:succeeded :succeeded :succeeded] (mapv :delivery/state delivered)))
      (is (= 3 (count @(:opened w))))
      (is (= 1 (count (fx/pushed-branches w)))))
    (testing "given delivery again → the recorded PRs, none opened twice, and no git command runs"
      (let [commands (count (fx/git-commands w))]
        (is (= delivered (deliver-all w ws)))
        (is (= commands (count (fx/git-commands w)))))
      (is (= 3 (count @(:opened w)))))))

(deftest ^{:stratum 1} test-the-base-ref-is-fetched-kept-and-the-recorded-head-pushed
  (let [w (fx/world)
        repo-id (first fx/allowed)
        path (str "/work/repos/" repo-id)
        url (str fx/repo-base-url repo-id ".git")
        ws (workspace/clone! (:wc w) (:acting w) (:lease w) (:grant w)
                             (assoc-in (fx/request [repo-id]) [:workspace/repos 0 :repo/base-ref] "develop"))
        branch (get-in ws [:workspace/checkouts 0 :repo/task-branch])]
    (deliver-all w ws)
    (is (some #{["git" "-C" path "fetch" "--quiet" "--no-tags" "--" url "develop"]} (fx/git-commands w)))
    (is (some #{["git" "-C" path "checkout" "--quiet" "-B" branch "FETCH_HEAD"]} (fx/git-commands w)))
    (is (= ["develop" fx/head-sha] ((juxt :repo/base-ref :repo/head-sha) (first @(:opened w)))))
    (is (some #{["git" "-C" path "push" "--porcelain" url (str fx/head-sha ":refs/heads/" branch)]} (fx/git-commands w)))))

(deftest ^{:stratum 1} test-a-repository-outside-the-grant-is-refused-before-anything-runs
  (let [w (fx/world)]
    (is (= :anomalies.agent-workspace/repo-not-allowed
           (anomaly/subtype (clone w (conj fx/allowed "acme-example/unrelated")))))
    (is (empty? (fx/git-commands w)))))

(deftest ^{:stratum 1} test-urls-come-from-the-repository-id-never-the-request
  (let [w (fx/world)
        ws (clone w (take 1 fx/allowed))
        url (str fx/repo-base-url (first fx/allowed) ".git")]
    (deliver-all w ws)
    (is (some #(= ["git" "clone" "--no-tags" "--quiet" "--" url (str "/work/repos/" (first fx/allowed))] %)
              (fx/git-commands w)))
    (is (some #(and (some #{"push"} %) (some #{url} %)) (fx/git-commands w)))))

(deftest ^{:stratum 1} test-a-handed-branch-or-path-is-refused-and-nothing-is-pushed
  (let [w (fx/world)
        ws (clone w (take 1 fx/allowed))]
    (doseq [forged [(assoc-in ws [:workspace/checkouts 0 :repo/task-branch] "main")
                    (assoc-in ws [:workspace/checkouts 0 :repo/path] "/work/elsewhere")]]
      (is (= :anomalies.agent-workspace/checkout-mismatch (anomaly/subtype (deliver-all w forged)))))
    (is (= :invalid-input (:anomaly/type (deliver-all w (update ws :workspace/lease dissoc :lease/generation)))))
    (is (empty? (fx/pushed-branches w)))))

(deftest ^{:stratum 1} test-a-lost-push-awaits-reconciliation-and-a-rejected-one-failed
  (doseq [[answer state] [[(anomaly/anomaly :timeout "exec deadline" {}) :unknown-outcome]
                          [{:exec/exit 128 :exec/stdout "" :exec/stderr "fatal: the remote end hung up unexpectedly"} :unknown-outcome]
                          [{:exec/exit 1 :exec/stdout "!\trefs/heads/x:refs/heads/x\t[rejected] (fetch first)\n" :exec/stderr ""} :failed]]]
    (let [w (fx/world nil (partial fx/push-answered answer))]
      (is (= [state] (mapv :delivery/state (deliver-all w (clone w (take 1 fx/allowed))))))
      (is (empty? @(:opened w))))))

(deftest ^{:stratum 1} test-a-failed-git-command-keeps-its-output
  (let [missing {:exec/exit 128 :exec/stdout "" :exec/stderr "fatal: repository not found"}
        w (fx/world nil #(if (some #{"clone"} (:exec/argv %)) missing (fx/answer-git %)))]
    (is (= "fatal: repository not found" (get-in (clone w (take 1 fx/allowed)) [:anomaly/data :exec/stderr])))))

(deftest ^{:stratum 1} test-an-unanswered-pr-stays-unresolved-and-is-not-reopened
  (doseq [port [port-down (constantly nil) (constantly {:pr/number 0 :pr/url ""})]]
    (let [w (fx/world port)
          ws (clone w (take 1 fx/allowed))]
      (is (= [:unknown-outcome] (mapv :delivery/state (deliver-all w ws))))
      (is (= [:unknown-outcome] (mapv :delivery/state (deliver-all w ws)))))))
