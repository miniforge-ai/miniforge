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

(ns ai.miniforge.bb-platform.core-test
  "Unit tests for bb-platform. Layer 0 (pure) is exhaustively covered;
   Layer 1 has a couple of light tests that touch the live PATH only
   via `installed?` against a binary every Unix host has (`echo`)."
  (:require [ai.miniforge.bb-platform.core :as sut]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0
;; Pure: OS detection

(deftest test-detect-os-macos
  (testing "Mac OS X (the value Java actually returns on macOS)"
    (is (= :macos (sut/detect-os "Mac OS X"))))
  (testing "macOS marketing name"
    (is (= :macos (sut/detect-os "macOS Sequoia"))))
  (testing "Darwin (some JVMs / native compiles report this)"
    (is (= :macos (sut/detect-os "Darwin")))))

(deftest test-detect-os-windows
  (testing "Windows desktop"
    (is (= :windows (sut/detect-os "Windows 11"))))
  (testing "Windows Server"
    (is (= :windows (sut/detect-os "Windows Server 2022")))))

(deftest test-detect-os-linux-default
  (testing "explicit Linux"
    (is (= :linux (sut/detect-os "Linux"))))
  (testing "anything unknown defaults to Linux"
    (is (= :linux (sut/detect-os "FreeBSD"))))
  (testing "blank/nil defaults to Linux"
    (is (= :linux (sut/detect-os nil)))
    (is (= :linux (sut/detect-os "")))))

;------------------------------------------------------------------------------ Layer 0
;; Pure: formula → cmd

(deftest test-formula-cmd-extraction
  (testing "tap/path-style formula keeps only the trailing segment"
    (is (= "clj-kondo"  (sut/formula->cmd "borkdude/brew/clj-kondo")))
    (is (= "clojure"    (sut/formula->cmd "clojure/tools/clojure")))
    (is (= "poly"       (sut/formula->cmd "polyfy/polylith/poly"))))
  (testing "bare formula name passes through"
    (is (= "babashka"        (sut/formula->cmd "babashka")))
    (is (= "markdownlint-cli" (sut/formula->cmd "markdownlint-cli")))))

;------------------------------------------------------------------------------ Layer 0
;; Pure: install plans

(deftest test-install-plan-skips-when-installed
  (testing "any OS short-circuits to :skip when the cmd is already on PATH"
    (doseq [os [:macos :linux :windows]]
      (let [plan (sut/install-plan {:formula "borkdude/brew/clj-kondo"
                                    :os os :installed? true})]
        (is (= :skip            (:action plan)))
        (is (= "clj-kondo"      (:package plan)))
        (is (= :already-installed (:reason plan)))))))

(deftest test-install-plan-macos-uses-brew-with-full-formula
  (testing "macOS keeps the tap-prefixed formula intact"
    (is (= {:action :run :package "clj-kondo"
            :command ["brew" "install" "borkdude/brew/clj-kondo"]}
           (sut/install-plan {:formula "borkdude/brew/clj-kondo"
                              :os :macos :installed? false})))))

(deftest test-install-plan-windows-uses-scoop-with-cmd-only
  (testing "Windows strips the tap prefix and uses the canonical cmd name"
    (is (= {:action :run :package "clj-kondo"
            :command ["scoop" "install" "clj-kondo"]}
           (sut/install-plan {:formula "borkdude/brew/clj-kondo"
                              :os :windows :installed? false})))))

(deftest test-install-plan-linux-returns-known-hint
  (testing "Linux returns a manual install hint when the cmd is in the table"
    (let [plan (sut/install-plan {:formula "borkdude/brew/clj-kondo"
                                  :os :linux :installed? false})]
      (is (= :hint        (:action plan)))
      (is (= "clj-kondo"  (:package plan)))
      (is (string? (:hint plan)))
      (is (re-find #"clj-kondo" (:hint plan))))))

(deftest test-install-plan-linux-falls-back-when-unknown
  (testing "Linux returns a generic hint for cmds not in the hint table"
    (let [plan (sut/install-plan {:formula "some-obscure-formula"
                                  :os :linux :installed? false})]
      (is (= :hint                   (:action plan)))
      (is (= "some-obscure-formula"  (:package plan)))
      (is (string? (:hint plan))))))

;------------------------------------------------------------------------------ Layer 0
;; Pure: upgrade plans

(deftest test-upgrade-plan-macos
  (is (= ["brew" "upgrade" "babashka"]
         (:command (sut/upgrade-plan {:formula "babashka" :os :macos})))))

(deftest test-upgrade-plan-windows
  (is (= ["scoop" "update" "babashka"]
         (:command (sut/upgrade-plan {:formula "babashka" :os :windows})))))

(deftest test-upgrade-plan-linux-hint
  (is (= :hint (:action (sut/upgrade-plan {:formula "babashka" :os :linux})))))

;------------------------------------------------------------------------------ Layer 0
;; Pure: java + markdownlint special cases

(deftest test-install-plan-java-mac-uses-cask
  (is (= ["brew" "install" "--cask" "temurin@21"]
         (:command (sut/install-plan-java {:os :macos :installed? false})))))

(deftest test-install-plan-java-windows-uses-scoop-jdk
  (is (= ["scoop" "install" "temurin21-jdk"]
         (:command (sut/install-plan-java {:os :windows :installed? false})))))

(deftest test-install-plan-java-skips-when-installed
  (is (= :skip (:action (sut/install-plan-java {:os :macos :installed? true})))))

(deftest test-install-plan-markdownlint-mac-uses-brew
  (is (= ["brew" "install" "markdownlint-cli"]
         (:command (sut/install-plan-markdownlint
                    {:os :macos :installed? false :npm-installed? true})))))

(deftest test-install-plan-markdownlint-non-mac-uses-npm-when-available
  (testing "Linux + npm → npm install"
    (is (= ["npm" "install" "-g" "markdownlint-cli"]
           (:command (sut/install-plan-markdownlint
                      {:os :linux :installed? false :npm-installed? true})))))
  (testing "Windows + npm → npm install (same as Linux)"
    (is (= ["npm" "install" "-g" "markdownlint-cli"]
           (:command (sut/install-plan-markdownlint
                      {:os :windows :installed? false :npm-installed? true}))))))

(deftest test-install-plan-markdownlint-falls-back-to-hint-without-npm
  (let [plan (sut/install-plan-markdownlint
              {:os :linux :installed? false :npm-installed? false})]
    (is (= :hint (:action plan)))
    (is (re-find #"npm install" (:hint plan)))))

;------------------------------------------------------------------------------ Layer 0
;; Pure: check assembly

(deftest test-check-passes-through-input-fields
  (testing "check is pure data assembly — every key round-trips"
    (let [report (sut/check {:os-name      "Mac OS X"
                             :os-arch      "aarch64"
                             :os           :macos
                             :shell        "/bin/zsh"
                             :java-version "21.0.4"
                             :bb-version   "1.3.0"
                             :tools        {"java" true "missing-cmd" false}})]
      (is (= "Mac OS X"  (:os-name report)))
      (is (= "aarch64"   (:os-arch report)))
      (is (= :macos      (:os report)))
      (is (= "/bin/zsh"  (:shell report)))
      (is (= "21.0.4"    (:java-version report)))
      (is (= "1.3.0"     (:bb-version report)))
      (is (true?  (get-in report [:tools "java"])))
      (is (false? (get-in report [:tools "missing-cmd"]))))))

;------------------------------------------------------------------------------ Layer 1
;; Side-effecting: light coverage (no shelling out)

(deftest test-installed-against-known-binary
  (testing "echo is on PATH on every Unix host the test runner runs on"
    (is (true? (sut/installed? "echo"))))
  (testing "made-up name is not on PATH"
    (is (false? (sut/installed? "definitely-not-a-real-cmd-bb-platform-test")))))

(deftest test-os-key-is-one-of-the-three-keywords
  (testing "regardless of what os.name returns, os-key is in the closed set"
    (is (contains? #{:macos :linux :windows} (sut/os-key)))))
