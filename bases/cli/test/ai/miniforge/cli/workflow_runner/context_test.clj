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
(ns ai.miniforge.cli.workflow-runner.context-test
  (:require
   [clojure.test :refer [deftest is testing]]
   [ai.miniforge.cli.worktree :as worktree]
   [ai.miniforge.cli.config :as config]
   [ai.miniforge.cli.workflow-runner.context :as sut]
   [ai.miniforge.event-stream.interface :as es]
   [ai.miniforge.tenancy.interface :as tenancy]))

;------------------------------------------------------------------------------ Layer 0

(deftest ^{:stratum 0} create-workflow-context-prefers-explicit-execution-worktree-test
  (testing "execution-opts worktree-path overrides discovered repo root"
    (with-redefs [worktree/worktree-root (constantly "/tmp/repo-root")
                  es/create-streaming-callback (fn [& _] nil)
                  es/workflow-started (fn [& _] {})
                  es/publish! (fn [& _] nil)]
      (let [context (sut/create-workflow-context
                     {:callbacks {}
                      :event-stream :stream
                      :workflow-id (random-uuid)
                      :workflow-type :canonical-sdlc
                      :workflow-version "1.0.0"
                      :source-dir "/tmp/repo-root/work"
                      :execution-opts {:worktree-path "/tmp/execution-worktree"}})]
        (is (= "/tmp/execution-worktree" (:worktree-path context)))
        (is (= "/tmp/repo-root" (:source-root context)))
        (is (= {:worktree-path "/tmp/execution-worktree"}
               (:execution/opts context)))))))

(deftest ^{:stratum 0} create-workflow-context-falls-back-to-discovered-worktree-test
  (testing "repo root is used only when no explicit execution worktree is present"
    (with-redefs [worktree/worktree-root (constantly "/tmp/repo-root")
                  es/create-streaming-callback (fn [& _] nil)
                  es/workflow-started (fn [& _] {})
                  es/publish! (fn [& _] nil)]
      (let [context (sut/create-workflow-context
                     {:callbacks {}
                      :event-stream :stream
                      :workflow-id (random-uuid)
                      :workflow-type :canonical-sdlc
                      :workflow-version "1.0.0"})]
        (is (= "/tmp/repo-root" (:worktree-path context)))
        (is (= "/tmp/repo-root" (:source-root context)))))))

(defn- ^{:stratum 0} context-with-config
  "Build a workflow context with `cfg` standing in for the user config."
  ([cfg] (context-with-config cfg false))
  ([cfg quiet]
   (with-redefs [config/load-config (constantly cfg)
                 worktree/worktree-root (constantly "/tmp/repo-root")
                 es/create-streaming-callback (fn [& _] nil)
                 es/workflow-started (fn [& _] {})
                 es/publish! (fn [& _] nil)]
     (sut/create-workflow-context
      {:callbacks {}
       :event-stream :stream
       :workflow-id (random-uuid)
       :workflow-type :canonical-sdlc
       :workflow-version "1.0.0"
       :quiet quiet}))))

(deftest ^{:stratum 0} an-explicit-acting-context-is-not-overridden-test
  ;; A future non-CLI boundary (MCP, daemon) resolves its own identity
  ;; and passes it in. This one must not re-resolve over the top of it —
  ;; that would be the second answer the design exists to prevent.
  (let [supplied (tenancy/establish-acting
                  (tenancy/resolve-operator {:tenancy {:operator-name "someone-else"}})
                  (java.time.Instant/parse "2026-08-01T00:00:00Z"))]
    (with-redefs [config/load-config (constantly {:tenancy {:operator-name "chris"}})
                  worktree/worktree-root (constantly "/tmp/repo-root")
                  es/create-streaming-callback (fn [& _] nil)
                  es/workflow-started (fn [& _] {})
                  es/publish! (fn [& _] nil)]
      (let [context (sut/create-workflow-context
                     {:callbacks {}
                      :event-stream :stream
                      :workflow-id (random-uuid)
                      :workflow-type :canonical-sdlc
                      :workflow-version "1.0.0"
                      :acting supplied})]
        (is (= supplied (:acting context)))))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} a-configured-operator-reaches-the-run-test
  ;; The boundary resolves once and puts the answer where create-context
  ;; will lift it onto :execution/acting.
  (let [context (context-with-config {:tenancy {:operator-name "chris"}})
        acting (:acting context)]
    (is (tenancy/valid-acting? acting))
    (is (= (get-in (tenancy/resolve-operator {:tenancy {:operator-name "chris"}})
                   [:identity/tenant :tenant/id])
           (:acting/tenant-id acting))
        "the tenant is the configured operator's, not a fresh one")))

(deftest ^{:stratum 1} no-configured-operator-attaches-nothing-test
  ;; Ariadne 3b attaches when configured and does NOT require. Nothing
  ;; configures an operator yet, so requiring one here would fail every
  ;; run in existence. The refusal lands in 3c, where records get owners
  ;; and an absent identity has something real to protect.
  ;; A blank name is a MISCONFIGURATION, not an absence — it warns, and
  ;; it belongs in the test below that captures output.
  (doseq [cfg [{} {:tenancy {}} {:tenancy {:operator-name nil}}]]
    (let [context (context-with-config cfg)]
      (is (nil? (:acting context))
          (str "no operator configured must attach nothing, given " (pr-str cfg)))
      (is (not (contains? context :acting))
          "and must not leave a nil :acting key for a later reader to trust")))
  (testing "the run still proceeds — the context is otherwise intact"
    (is (= "/tmp/repo-root" (:worktree-path (context-with-config {}))))))

(deftest ^{:stratum 1} a-misconfigured-operator-is-visible-not-silent-test
  ;; Configured-and-wrong is not the same as not-configured. Swallowing
  ;; both would leave the run unowned while the operator believed they
  ;; had set an identity, and the resolver's distinct refusal causes
  ;; would be unobservable.
  (doseq [bad [42 {:a 1} [1 2] "  "]]
    (let [out (java.io.StringWriter.)
          context (binding [*out* out] (context-with-config {:tenancy {:operator-name bad}}))]
      (is (nil? (:acting context))
          (str "a misconfigured operator still attaches nothing, given " (pr-str bad)))
      (is (re-find #"(?i)warning" (str out))
          (str "but it must say so, given " (pr-str bad)))))
  (testing "while a merely absent operator stays quiet — that is the expected state today"
    (let [out (java.io.StringWriter.)]
      (binding [*out* out] (context-with-config {}))
      (is (not (re-find #"(?i)warning" (str out)))))))

(deftest ^{:stratum 1} quiet-suppresses-the-misconfiguration-warning-test
  ;; A --quiet run that still prints is a run whose output cannot be
  ;; piped. Every other diagnostic on this path respects the flag.
  (let [out (java.io.StringWriter.)
        context (binding [*out* out]
                  (context-with-config {:tenancy {:operator-name 42}} true))]
    (is (nil? (:acting context)) "still attaches nothing")
    (is (= "" (str out)) "and says nothing when asked to be quiet")))
