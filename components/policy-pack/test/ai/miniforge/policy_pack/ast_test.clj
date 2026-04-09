;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0

(ns ai.miniforge.policy-pack.ast-test
  "Tests for tree-sitter CLI wrapper — file extension, language detection, violations."
  (:require
   [clojure.test :refer [deftest testing is]]
   [ai.miniforge.policy-pack.ast :as sut]
   [ai.miniforge.policy-pack.schema :as schema]))

(deftest file-extension-test
  (testing "extracts extension from simple filename"
    (is (= "clj" (sut/file-extension "core.clj"))))

  (testing "extracts extension from full path"
    (is (= "py" (sut/file-extension "src/main/app.py"))))

  (testing "handles multiple dots"
    (is (= "edn" (sut/file-extension "pack.test.edn"))))

  (testing "returns nil for no extension"
    (is (nil? (sut/file-extension "Makefile")))))

(deftest detect-language-test
  (testing "detects Clojure from .clj extension"
    (is (= "clojure" (sut/detect-language "core.clj" nil))))

  (testing "detects Python from .py extension"
    (is (= "python" (sut/detect-language "app.py" nil))))

  (testing "lang override takes priority"
    (is (= "rust" (sut/detect-language "file.clj" "rust"))))

  (testing "unknown extension returns text"
    (is (= "text" (sut/detect-language "file.xyz" nil)))))

(deftest matches->violations-test
  (testing "converts matches to violation-shaped maps"
    (let [matches [{:row 5 :col 0 :capture "fn" :text "eval" :pattern 0}
                   {:row 10 :col 2 :capture "fn" :text "eval" :pattern 0}]
          result  (sut/matches->violations matches :test/no-eval "200" "No eval" "src/core.clj")]
      (is (= 2 (count result)))
      (is (= :test/no-eval (:rule/id (first result))))
      (is (= 6 (:line (first result))))  ;; 0-indexed row 5 → 1-indexed line 6
      (is (= "eval" (:current (first result))))))

  (testing "returns empty vector for no matches"
    (is (= [] (sut/matches->violations [] :test/rule "200" "Title" "file.clj")))))

(deftest tree-sitter-available-test
  (testing "returns boolean"
    (is (boolean? (sut/tree-sitter-available?)))))

(deftest state-comparison-detection-test
  (testing "detects drift between desired and current state"
    (let [detect (requiring-resolve 'ai.miniforge.policy-pack.detection/detect-state-comparison)
          rule    {:rule/id :test/drift :rule/enforcement {:action :warn :message "Drift"}}
          context {:desired-state {:replicas 3 :image "v2"}
                   :current-state {:replicas 1 :image "v1"}}
          result  (detect rule {} context)]
      (is (some? result))
      (is (= :state-comparison (:type result)))))

  (testing "returns nil when no drift"
    (let [detect  (requiring-resolve 'ai.miniforge.policy-pack.detection/detect-state-comparison)
          rule    {:rule/id :test/drift :rule/enforcement {:action :warn :message "Drift"}}
          context {:desired-state {:replicas 3} :current-state {:replicas 3}}
          result  (detect rule {} context)]
      (is (nil? result)))))

(deftest plan-resource-counts-test
  (testing "counts creates, updates, destroys from plan output"
    (let [counts (requiring-resolve 'ai.miniforge.policy-pack.detection/plan-resource-counts)
          plan   "# aws_vpc.main will be created\n# aws_route.old will be destroyed\n# aws_subnet.main will be updated"]
      (is (= {:creates 1 :updates 1 :destroys 1} (counts plan)))))

  (testing "empty plan returns zeros"
    (let [counts (requiring-resolve 'ai.miniforge.policy-pack.detection/plan-resource-counts)]
      (is (= {:creates 0 :updates 0 :destroys 0} (counts "no resources"))))))
