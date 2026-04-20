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

(ns ai.miniforge.bb-generate-icon.core-test
  "`plan` is exhaustively tested as a pure function. `run!` is
   end-to-end over sips/iconutil/swift — exercised via a consumer's
   `bb generate-icon` invocation rather than duplicated here."
  (:require [clojure.test :refer [deftest testing is]]
            [ai.miniforge.bb-generate-icon.core :as sut]))

;------------------------------------------------------------------------------ Layer 0
;; Plan resolution — pure.

(def ^:private base-cfg
  {:root           "/tmp/repo"
   :output-dir     "dist"
   :icns-name      "AppIcon.icns"
   :iconset-name   "AppIcon.iconset"
   :sizes          [16 32 128]
   :default-source "product/AppIcon.png"})

(deftest test-plan-resolves-paths-relative-to-root
  (testing "given relative paths + :root → absolute output paths"
    (let [p (sut/plan base-cfg)]
      (is (= "/tmp/repo/dist"                 (:output-dir p)))
      (is (= "/tmp/repo/dist/AppIcon.icns"    (:icns-path p)))
      (is (= "/tmp/repo/dist/AppIcon.iconset" (:iconset-dir p)))
      (is (= "/tmp/repo/product/AppIcon.png"  (:default-source p))))))

(deftest test-plan-preserves-sizes
  (testing "given :sizes → same vector returned in plan"
    (is (= [16 32 128] (:sizes (sut/plan base-cfg))))
    (is (= [512]       (:sizes (sut/plan (assoc base-cfg :sizes [512])))))))

(deftest test-plan-resolves-placeholder-when-present
  (testing "given :placeholder → both sub-paths resolved"
    (let [p (sut/plan (assoc base-cfg
                             :placeholder {:path       "dist/AppIcon_1024.png"
                                           :swift-file "dev/placeholder.swift"}))]
      (is (= "/tmp/repo/dist/AppIcon_1024.png"
             (get-in p [:placeholder :path])))
      (is (= "/tmp/repo/dev/placeholder.swift"
             (get-in p [:placeholder :swift-file]))))))

(deftest test-plan-omits-placeholder-when-absent
  (testing "given no :placeholder → plan has nil placeholder"
    (is (nil? (:placeholder (sut/plan base-cfg))))))

(deftest test-plan-honors-absolute-paths
  (testing "given an absolute path in cfg → used as-is, not re-rooted"
    (let [p (sut/plan (assoc base-cfg :default-source "/abs/source.png"))]
      (is (= "/abs/source.png" (:default-source p))))))

;------------------------------------------------------------------------------ Rich Comment
(comment
  (clojure.test/run-tests 'ai.miniforge.bb-generate-icon.core-test)

  :leave-this-here)
