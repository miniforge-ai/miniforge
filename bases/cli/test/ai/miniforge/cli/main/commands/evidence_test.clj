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

(ns ai.miniforge.cli.main.commands.evidence-test
  "Unit tests for evidence bundle CLI commands."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [babashka.fs :as fs]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.main.commands.evidence :as sut]
   [ai.miniforge.cli.main.commands.shared :as shared]))

;------------------------------------------------------------------------------ Layer 0: Fixtures & factories

(def ^:dynamic *tmp-dir* nil)

(defn tmp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "evidence-test-"}))]
    (binding [*tmp-dir* dir]
      (try
        (f)
        (finally
          (fs/delete-tree dir))))))

(use-fixtures :each tmp-dir-fixture)

(defn- make-artifact
  "Factory for a single bundle artifact entry. Defaults to a code artifact;
   override `:artifact/type` or `:artifact/id` to vary per test."
  [& {:as overrides}]
  (merge {:artifact/type :code
          :artifact/id   "art-1"}
         overrides))

(defn- make-phase
  "Factory for a phase entry under `:evidence/plan` or `:evidence/implement`."
  [phase-name & {:as overrides}]
  (merge {:phase/name phase-name}
         overrides))

(defn- make-outcome
  "Factory for an `:evidence/outcome` value."
  [& {:as overrides}]
  (merge {:outcome/success false}
         overrides))

(defn- make-dependency-health
  "Factory for a single `:evidence/dependency-health` entry."
  [& {:as overrides}]
  (merge {:dependency/id     :anthropic
          :dependency/status :degraded}
         overrides))

(defn- make-failure-attribution
  "Factory for an `:evidence/failure-attribution` map."
  [& {:as overrides}]
  (merge {:failure/source   :external-provider
          :failure/vendor   :anthropic
          :dependency/id    :anthropic
          :dependency/class :rate-limit}
         overrides))

(defn- make-bundle
  "Factory for a minimal legacy evidence bundle. Pass overrides as kwargs."
  [& {:as overrides}]
  (merge {:bundle/id          "bundle-1"
          :bundle/workflow-id "wf-1"
          :bundle/status      "complete"
          :bundle/created-at  "2026-04-13T10:00:00Z"
          :bundle/artifacts   [(make-artifact)]
          :bundle/phases      [:plan :implement]}
         overrides))

(defn- make-list-bundle
  "Factory for the minimal bundle shape returned by the resolver in list output."
  [& {:as overrides}]
  (merge {:bundle/id          "b-1"
          :bundle/workflow-id "wf-1"
          :bundle/status      "complete"}
         overrides))

(defn- make-canonical-bundle
  "Factory for a canonical-form evidence bundle including dependency-health
   and failure-attribution. Pass overrides as kwargs."
  [& {:as overrides}]
  (merge {:evidence-bundle/id           "bundle-2"
          :evidence-bundle/workflow-id  "wf-2"
          :evidence-bundle/created-at   "2026-04-30T10:00:00Z"
          :evidence/plan                (make-phase :plan)
          :evidence/implement           (make-phase :implement)
          :evidence/outcome             (make-outcome)
          :evidence/dependency-health   {:anthropic (make-dependency-health)}
          :evidence/failure-attribution (make-failure-attribution)}
         overrides))

;------------------------------------------------------------------------------ Layer 1: Tests

(deftest evidence-list-cmd-no-component-empty-dir-test
  (testing "list command shows 'no bundles' when dir is empty"
    (with-redefs [shared/try-resolve-fn (constantly nil)
                  app-config/home-dir (constantly *tmp-dir*)]
      (let [output (with-out-str (sut/evidence-list-cmd {}))]
        (is (re-find #"(?i)no evidence" output))))))

(deftest evidence-list-cmd-component-results-test
  (testing "list command displays component results when available"
    (with-redefs [shared/try-resolve-fn
                  (constantly [(make-list-bundle)])]
      (let [output (with-out-str (sut/evidence-list-cmd {}))]
        (is (.contains output "b-1"))))))

(deftest evidence-show-cmd-missing-id-test
  (testing "show command exits with error when no id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/evidence-show-cmd {}))
        (is @exited?)))))

(deftest evidence-show-cmd-not-found-test
  (testing "show command reports not found for unknown bundle"
    (let [exited? (atom false)]
      (with-redefs [shared/try-resolve-fn (constantly nil)
                    app-config/home-dir (constantly *tmp-dir*)
                    shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/evidence-show-cmd {:id "missing"}))
        (is @exited?)))))

(deftest evidence-show-cmd-with-bundle-test
  (testing "show command displays bundle details from filesystem"
    (let [evidence-path (str *tmp-dir* "/evidence")]
      (fs/create-dirs evidence-path)
      (spit (str evidence-path "/test-bundle.edn") (pr-str (make-bundle)))
      (with-redefs [shared/try-resolve-fn (constantly nil)
                    app-config/home-dir (constantly *tmp-dir*)]
        (let [output (with-out-str (sut/evidence-show-cmd {:id "test-bundle"}))]
          (is (.contains output "wf-1")))))))

(deftest evidence-show-cmd-with-canonical-bundle-test
  (testing "show command normalizes canonical evidence bundle fields"
    (let [evidence-path (str *tmp-dir* "/evidence")]
      (fs/create-dirs evidence-path)
      (spit (str evidence-path "/canonical-bundle.edn") (pr-str (make-canonical-bundle)))
      (with-redefs [shared/try-resolve-fn (constantly nil)
                    app-config/home-dir (constantly *tmp-dir*)]
        (let [output (with-out-str (sut/evidence-show-cmd {:id "canonical-bundle"}))]
          (is (.contains output "wf-2"))
          (is (.contains output "failed"))
          (is (.contains output "external-provider / anthropic / rate-limit"))
          (is (.contains output "Dependencies: 1")))))))

(deftest evidence-export-cmd-missing-id-test
  (testing "export command exits with error when no id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/evidence-export-cmd {}))
        (is @exited?)))))

(deftest load-bundle-from-file-test
  (testing "loads valid EDN file"
    (let [f (java.io.File. (str *tmp-dir* "/test.edn"))]
      (spit f (pr-str {:bundle/id "b-1"}))
      (is (= {:bundle/id "b-1"} (sut/load-bundle-from-file f)))))

  (testing "returns nil for non-EDN file"
    (let [f (java.io.File. (str *tmp-dir* "/test.json"))]
      (spit f "{}")
      (is (nil? (sut/load-bundle-from-file f))))))
