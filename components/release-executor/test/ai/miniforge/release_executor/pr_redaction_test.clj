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
(ns ai.miniforge.release-executor.pr-redaction-test
  "PR title and body are redacted before gh publishes them, on the host
   (git.clj) and sandbox backends."
  (:require
   [ai.miniforge.redaction.interface :as redaction]
   [ai.miniforge.release-executor.git :as git]
   [ai.miniforge.release-executor.sandbox :as sandbox]
   [babashka.process :as process]
   [clojure.string :as str]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} fake-token
  "Shaped like a GitHub classic token; not a real credential. Built from
   parts so the source holds no token-shaped literal."
  (str "ghp" "_" "abcdefghijklmnopqrstuvwxyz0123456789"))

(defn- ^{:stratum 0} capture-host-gh
  "Call `f` with the host backend's gh process captured; returns each argv.
   Mocks process/process as well as process/shell so the real gh never
   runs, whichever of the two the backend starts it with."
  [f]
  (let [calls (atom [])
        ok    {:exit 0 :out "https://github.com/o/r/pull/7\n" :err ""}]
    (with-redefs [process/shell   (fn [_opts & args] (swap! calls conj (vec args)) ok)
                  process/process (fn [_opts & args] (swap! calls conj (vec args)) (delay ok))]
      (f))
    @calls))

(defn- ^{:stratum 0} capture-sandbox-cmds
  "Call `f` with sandbox/exec! captured; returns each command string."
  [f]
  (let [cmds (atom [])]
    (with-redefs [sandbox/exec! (fn [_executor _env-id cmd & _]
                                  (swap! cmds conj cmd)
                                  {:success? true :output "https://github.com/o/r/pull/7"})]
      (f))
    @cmds))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} host-backend-redacts-pr-text-test
  (let [title (str "Fix push with " fake-token)
        body  (str "Pushed using " fake-token " as GH_TOKEN.")]
    (testing "git/create-pr! passes neither the title's nor the body's token to gh"
      (let [argv (first (capture-host-gh
                         #(git/create-pr! "/tmp/wt" {:title title :body body :base-branch "main"} "tok")))]
        (is (= ["gh" "pr" "create"] (subvec argv 0 3)))
        (is (not-any? #(str/includes? % fake-token) argv))
        (is (some #{(str "Fix push with " (redaction/marker))} argv))
        (is (some #{(str "Pushed using " (redaction/marker) " as GH_TOKEN.")} argv))))
    (testing "git/edit-pr-body! passes no token to gh"
      (let [argv (first (capture-host-gh #(git/edit-pr-body! "/tmp/wt" 7 body "tok")))]
        (is (= ["gh" "pr" "edit" "7"] (subvec argv 0 4)))
        (is (not-any? #(str/includes? % fake-token) argv))
        (is (some #{(str "Pushed using " (redaction/marker) " as GH_TOKEN.")} argv))))))

(deftest ^{:stratum 1} sandbox-backend-redacts-pr-text-test
  (let [title (str "Fix push with " fake-token)
        body  (str "Pushed using " fake-token " as GH_TOKEN.")]
    (testing "sandbox/create-pr! puts no token in the gh command"
      (let [cmd (first (capture-sandbox-cmds
                        #(sandbox/create-pr! :executor "env-1"
                                             {:title title :body body :base-branch "main"})))]
        (is (str/starts-with? cmd "gh pr create"))
        (is (not (str/includes? cmd fake-token)))
        (is (str/includes? cmd (str "--title 'Fix push with " (redaction/marker) "'")))
        (is (str/includes? cmd (str "Pushed using " (redaction/marker))))))
    (testing "sandbox/edit-pr-body! puts no token in the gh command"
      (let [cmd (first (capture-sandbox-cmds #(sandbox/edit-pr-body! :executor "env-1" 7 body)))]
        (is (str/starts-with? cmd "gh pr edit 7"))
        (is (not (str/includes? cmd fake-token)))
        (is (str/includes? cmd (str "Pushed using " (redaction/marker))))))))
