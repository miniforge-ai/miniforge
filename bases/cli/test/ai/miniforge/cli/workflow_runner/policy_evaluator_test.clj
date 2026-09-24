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
(ns ai.miniforge.cli.workflow-runner.policy-evaluator-test
  (:require
   [ai.miniforge.anomaly.interface :as anomaly]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.web.github :as github]
   [ai.miniforge.cli.workflow-runner.policy-evaluator :as sut]
   [clojure.java.io :as io]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} diff
  "diff --git a/main.tf b/main.tf\n--- a/main.tf\n+++ b/main.tf\n@@ -1 +1,2 @@\n x\n+y\n")

(def ^{:stratum 0} glob-scoped-pack
  "A pack that applies only to `*.tf` files."
  (str "{:pack/id \"tf\" :pack/name \"TF\" :pack/version \"1\" :pack/description \"\""
       " :pack/author \"\" :pack/categories [] :pack/rules []"
       " :pack/applies-to {:file-globs [\"*.tf\"]}"
       " :pack/created-at #inst \"2026-01-01\" :pack/updated-at #inst \"2026-01-01\"}"))

(defn- ^{:stratum 0} packs-dir-with
  "A temp packs dir holding `files` (name → content)."
  [files]
  (let [dir (.toFile (java.nio.file.Files/createTempDirectory
                      "mf-packs" (make-array java.nio.file.attribute.FileAttribute 0)))]
    (doseq [[file-name content] files] (spit (io/file dir file-name) content))
    (str dir)))

(defn- ^{:stratum 0} reason
  [result]
  (get-in result [:anomaly/data :failure/reason]))

(deftest ^{:stratum 0} pr-coordinates-test
  (testing "the console's wire details (string keys)"
    (is (= {:repo "o/r" :number 7}
           (sut/pr-coordinates {:policy/target-id "o/r#7"
                                :policy/details {"repo" "o/r" "number" 7}}))))
  (testing "the <repo>#<number> target id alone"
    (is (= {:repo "o/r" :number 12} (sut/pr-coordinates {:policy/target-id "o/r#12"}))))
  (testing "nothing that names a PR"
    (is (nil? (sut/pr-coordinates {:policy/target-id "wf-1"})))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} refusals-are-not-verdicts-test
  (let [request {:policy/target-id "o/r#7"}
        deps (fn [packs fetched-diff]
               {:evaluate-pr (fn [_ _] (throw (ex-info "must not evaluate" {})))
                :load-packs (constantly packs)
                :fetch-diff (constantly fetched-diff)})]
    (testing "each refusal is an anomaly naming its reason, never a pass"
      (is (= :no-pr (reason (sut/evaluate (deps {:loaded [{}]} diff) {:policy/target-id "wf-1"}))))
      (is (= :pack-load-failed
             (reason (sut/evaluate (deps {:loaded [{}] :failed [{:path "bad"}]} diff) request))))
      (is (= :no-policy-packs (reason (sut/evaluate (deps {:loaded []} diff) request))))
      (is (= :no-diff (reason (sut/evaluate (deps {:loaded [{}]} nil) request))))
      (is (anomaly/anomaly? (sut/evaluate (deps {:loaded []} diff) request))))))

(deftest ^{:stratum 1} evaluate-loads-installed-packs-and-fetches-the-diff-test
  (let [fetched (atom nil)]
    (with-redefs [github/fetch-pr-diff (fn [repo number] (reset! fetched [repo number]) diff)]
      (testing "installed packs from <home>/packs, the diff from gh, the real evaluator"
        (with-redefs [app-config/packs-dir (constantly (packs-dir-with {"tf.pack.edn" glob-scoped-pack}))]
          (let [result (sut/evaluate {:policy/target-id "o/r#7"})]
            (is (= ["o/r" 7] @fetched))
            (is (true? (:evaluation/passed? result)))
            (is (= ["tf"] (:evaluation/packs-applied result))
                "changed files come from the diff, so the glob-scoped pack applies"))))
      (testing "one pack that fails to load refuses the whole evaluation"
        (with-redefs [app-config/packs-dir (constantly (packs-dir-with {"tf.pack.edn" glob-scoped-pack
                                                                        "bad.pack.edn" "{:not a pack"}))]
          (is (= :pack-load-failed (reason (sut/evaluate {:policy/target-id "o/r#7"})))))))))
