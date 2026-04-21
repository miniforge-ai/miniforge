;; Title: Miniforge.ai
;; Subtitle: An agentic SDLC / fleet-control platform
;; Author: Christopher Lester
;; Line: Founder, Miniforge.ai (project)
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;;
;; Licensed under the Apache License, Version 2.0.

(ns ai.miniforge.bb-etl-runner.core-test
  (:require [ai.miniforge.bb-etl-runner.core :as core]
            [clojure.test :refer [deftest is testing]]))

(deftest deps-override-test
  (testing "builds :local/root deps map pointing at the pack src"
    (is (= "{:deps {local/pack {:local/root \"/abs/pack/src\"}}}"
           (core/deps-override "/abs/pack/src")))))

(deftest invocation-argv-test
  (testing "argv shape is stable and routes through :dev alias"
    (is (= ["clojure" "-Sdeps"
            "{:deps {local/pack {:local/root \"/p/src\"}}}"
            "-M:dev" "-m" "ai.thesium.etl.cli"
            "run" "/p/pipeline.edn"
            "--env" "/p/env.edn"
            "--output" "/p/out.json"]
           (core/invocation-argv
            {:pack-src "/p/src"
             :cli-ns   "ai.thesium.etl.cli"
             :pipeline "/p/pipeline.edn"
             :env      "/p/env.edn"
             :output   "/p/out.json"})))))
