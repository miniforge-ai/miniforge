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

(ns ai.miniforge.bb-dev-tools.core-test
  (:require [ai.miniforge.bb-dev-tools.core :as sut]
            [babashka.fs :as fs]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

(def sample-catalog
  {:tools
   {:coverage/cloverage
    {:tool/id :coverage/cloverage
     :tool/adapter :clojure/cloverage
     :tool/config {:alias-keys [:dev :test]
                   :output-dir "target/coverage/clj"}}
    :coverage/swift
    {:tool/id :coverage/swift
     :tool/adapter :external/command
     :tool/run {:cwd "thesium-app"
                :commands [["swift" "test" "--enable-code-coverage"]]}}
    :coverage/disabled
    {:tool/id :coverage/disabled
     :tool/enabled false
     :tool/adapter :external/command
     :tool/run {:commands [["echo" "disabled"]]}}}
   :toolsets
   {:toolset/coverage
    {:toolset/id :toolset/coverage
     :toolset/tools [:coverage/cloverage :coverage/swift :coverage/disabled]}}})

(defn- make-temp-repo!
  []
  (let [dir (fs/create-temp-dir {:prefix "bb-dev-tools-test"})
        deps-path (fs/path dir "deps.edn")]
    (spit (str deps-path)
          (pr-str {:paths ["tasks"]
                   :deps '{org.clojure/clojure {:mvn/version "1.12.0"}}
                   :aliases {:dev {:extra-paths ["mvp/src" "resources"]}
                             :test {:extra-paths ["test"]}}}))
    (str dir)))

(deftest resolve-toolset-tools-filters-disabled-tools
  (testing "toolset resolution keeps only enabled tools"
    (let [tools (sut/resolve-toolset-tools sample-catalog :toolset/coverage)]
      (is (= [:coverage/cloverage :coverage/swift]
             (mapv :tool/id tools))))))

(deftest stage-plans-build-external-command-steps
  (testing "external command tools resolve cwd relative to repo root"
    (let [plans (sut/stage-plans "/tmp/example"
                                 (get-in sample-catalog [:tools :coverage/swift])
                                 :run)
          plan (first plans)]
      (is (= "/tmp/example/thesium-app" (:cwd plan)))
      (is (= ["swift" "test" "--enable-code-coverage"] (:command plan))))))

(deftest stage-plans-build-cloverage-command
  (testing "cloverage tool derives a JVM command from repo deps.edn and tool config"
    (let [repo-root (make-temp-repo!)
          plans (sut/stage-plans repo-root
                                 (get-in sample-catalog [:tools :coverage/cloverage])
                                 :run)
          plan (first plans)
          command-string (str/join " " (:command plan))]
      (is (= "clojure" (first (:command plan))))
      (is (str/includes? command-string "cloverage.coverage"))
      (is (str/includes? command-string "--src-ns-path tasks"))
      (is (str/includes? command-string "--src-ns-path mvp/src"))
      (is (str/includes? command-string "--test-ns-path test"))
      (is (str/includes? command-string "target/coverage/clj")))))

(deftest stage-plans-build-cloverage-install-command
  (testing "cloverage install stage prefetches the tool dependency"
    (let [plans (sut/stage-plans "/tmp/example"
                                 (get-in sample-catalog [:tools :coverage/cloverage])
                                 :install)
          plan (first plans)
          command-string (str/join " " (:command plan))]
      (is (= "clojure" (first (:command plan))))
      (is (str/includes? command-string "cloverage"))
      (is (str/includes? command-string "-P")))))
