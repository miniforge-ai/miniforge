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
(ns ai.miniforge.release-executor.release-write-redaction-test
  "The release phase redacts what it commits to the repository: the
   commit message (host and sandbox backends) and the PR doc file, its
   name included. Ordinary text passes through unchanged."
  (:require
   [ai.miniforge.redaction.interface :as redaction]
   [ai.miniforge.release-executor.core :as core]
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.sandbox :as sandbox]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fixture
  "Secrets shaped like the real ones (none is a credential), built from
   parts so the source holds no token-shaped literal, and release
   metadata quoting each of them."
  (let [gh-token      (str "ghp" "_" "abcdefghijklmnopqrstuvwxyz0123456789")
        anthropic-key (str "sk" "-ant-api03-" "abcdefghijklmnopqrstuvwxyz0123456789")
        bearer-value  "Zm9vYmFyYmF6cXV4MTIzNDU2Nzg5MA"
        url-password  "hunter2-url-secret"]
    {:secrets       [gh-token anthropic-key bearer-value url-password]
     :release-meta  {:release/pr-title       (str "Fix push via https://bot:" url-password "@github.com")
                     :release/commit-message (str "Fix push; used GH_TOKEN=" gh-token)
                     :release/pr-description (str "Ran with ANTHROPIC_API_KEY=" anthropic-key
                                                  " and x-access-token:" gh-token)}
     :workflow-data {:review-artifacts
                     [{:review/decision :approved
                       :review/summary  (str "Header was Authorization: Bearer " bearer-value)}]}}))

(def ^{:stratum 0} ordinary-meta
  "Release text that resembles nothing secret and must survive."
  {:release/pr-title       "Raise the token budget"
   :release/commit-message (str "Raise the token budget; follows 3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b"
                                " for run 123e4567-e89b-12d3-a456-426614174000")
   :release/pr-description "Each token is counted once. See 3f2a9c1d8e7b6a5f4c3d2e1f0a9b8c7d6e5f4a3b."})

(defn- ^{:stratum 0} capture-host-shell
  "Call `f` with process/shell captured; returns each argv. Every git
   call succeeds and prints a sha."
  [f]
  (let [calls (atom [])]
    (with-redefs [process/shell (fn [_opts & args]
                                  (swap! calls conj (vec args))
                                  {:exit 0 :out "abc123\n" :err ""})]
      (f))
    @calls))

(defn- ^{:stratum 0} capture-sandbox-exec
  "Call `f` with sandbox/exec! and sandbox/write-file! captured; returns
   {:cmds [...] :writes [[path content] ...]}."
  [f]
  (let [cmds   (atom [])
        writes (atom [])]
    (with-redefs [sandbox/exec!       (fn [_executor _env-id cmd & _]
                                        (swap! cmds conj cmd)
                                        {:success? true :output "abc123"})
                  sandbox/write-file! (fn [_executor _env-id path content]
                                        (swap! writes conj [path content])
                                        {:success? true})]
      (f))
    {:cmds @cmds :writes @writes}))

(defn- ^{:stratum 0} capture-host-pr-doc
  "Run step-generate-pr-doc in host mode for `release-meta` with the git
   writes captured; returns {:result state :writes [[path content] ...]}."
  [release-meta workflow-data]
  (let [writes (atom [])]
    (with-redefs [git/write-file!  (fn [_worktree path content]
                                     (swap! writes conj [path content])
                                     {:success? true})
                  git/exec!        (fn [& _] {:success? true :output ""})
                  git/force-push!  (fn [& _] {:success? true :output ""})]
      {:result (core/step-generate-pr-doc
                {:create-pr?    true
                 :host-mode?    true
                 :worktree-path "/tmp/wt"
                 :branch        "mf/fix"
                 :pr-number     7
                 :pr-url        "https://github.com/o/r/pull/7"
                 :release-meta  release-meta
                 :workflow-data workflow-data})
       :writes @writes})))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} leaked
  "The fixture secrets that appear in TEXT."
  [text]
  (filterv #(str/includes? (str text) %) (:secrets fixture)))

(deftest ^{:stratum 1} ordinary-release-text-passes-unchanged-test
  (testing "a SHA, a UUID and the word token survive the commit message"
    (let [argv (first (capture-host-shell
                       #(git/commit-changes! "/tmp/wt" (:release/commit-message ordinary-meta))))]
      (is (= ["git" "commit" "-m" (:release/commit-message ordinary-meta)] argv))))
  (testing "they survive the PR doc and its file name"
    (let [{:keys [writes]} (capture-host-pr-doc ordinary-meta {})
          [path content]   (first writes)]
      (is (str/ends-with? path "-raise-the-token-budget.md"))
      (is (str/includes? content (:release/pr-description ordinary-meta)))
      (is (not (str/includes? content (redaction/marker)))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} host-commit-message-redacted-test
  (testing "git/commit-changes! hands git a message with no secret in it"
    (let [argv (first (capture-host-shell
                       #(git/commit-changes! "/tmp/wt"
                                             (get-in fixture [:release-meta :release/commit-message]))))]
      (is (= ["git" "commit" "-m"] (subvec argv 0 3)))
      (is (= [] (leaked argv)))
      (is (= (str "Fix push; used GH_TOKEN=" (redaction/marker)) (nth argv 3))))))

(deftest ^{:stratum 2} sandbox-commit-message-redacted-test
  (testing "step-commit on the sandbox backend runs git commit with no secret in it"
    (let [{:keys [cmds]} (capture-sandbox-exec
                          #(core/step-commit {:release-meta   (:release-meta fixture)
                                              :executor       :mock
                                              :environment-id "env-1"}))
          commit-cmd     (first (filter #(str/starts-with? % "git commit") cmds))]
      (is (some? commit-cmd))
      (is (= [] (leaked commit-cmd)))
      (is (str/includes? commit-cmd (redaction/marker))))))

(deftest ^{:stratum 2} host-pr-doc-redacted-test
  (testing "step-generate-pr-doc (host) writes and records a doc with no secret in it"
    (let [{:keys [result writes]} (capture-host-pr-doc (:release-meta fixture)
                                                       (:workflow-data fixture))
          [path content]          (first writes)]
      (is (= 1 (count writes)))
      (is (= [] (leaked path)) "the file name comes from the title")
      (is (= [] (leaked content)))
      (is (str/includes? content (redaction/marker)))
      (is (= content (:pr-doc-content result))))))

(deftest ^{:stratum 2} sandbox-pr-doc-redacted-test
  (let [state {:create-pr?     true
               :executor       :mock
               :environment-id "env-1"
               :branch         "mf/fix"
               :release-meta   (:release-meta fixture)
               :workflow-data  (:workflow-data fixture)}]
    (testing "step-generate-pr-doc (sandbox) writes a doc with no secret in it"
      (let [{:keys [writes]} (capture-sandbox-exec #(core/step-generate-pr-doc state))
            [path content]   (first writes)]
        (is (= [] (leaked path)))
        (is (= [] (leaked content)))
        (is (str/includes? content (redaction/marker)))))
    (testing "step-write-pr-doc writes a doc with no secret in it"
      (let [{:keys [writes]} (capture-sandbox-exec #(core/step-write-pr-doc state))
            [path content]   (first writes)]
        (is (= [] (leaked path)))
        (is (= [] (leaked content)))
        (is (str/includes? content (redaction/marker)))))))
