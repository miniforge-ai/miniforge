;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.workflow.checkpoint-spec-hash-test
  "A run's manifest records the spec file it started from, hashed at the
   first checkpoint, and `spec-drift` reports when that file changed."
  (:require [ai.miniforge.config.interface :as config]
            [ai.miniforge.workflow.checkpoint-store :as store]
            [ai.miniforge.workflow.checkpoint-test-support :as fixture]
            [ai.miniforge.workflow.context :as context]
            [ai.miniforge.workflow.interface :as workflow]
            [babashka.fs :as fs]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} spec-run
  "A run started from the spec file at `spec-path`, checkpointing under `root`."
  [root spec-path]
  (context/create-context {:workflow/id :spec-hash :workflow/version "1.0.0"
                           :workflow/pipeline [{:phase :plan}]}
                          {:spec/path spec-path}
                          {:checkpoint/root root}))

(defn- ^{:stratum 0} manifest [root ctx]
  (:manifest (workflow/load-checkpoint-data (:execution/id ctx) {:checkpoint/root root})))

(deftest ^{:stratum 0} spec-drift-reports-a-changed-or-missing-spec
  (fs/with-temp-dir [dir {:prefix "spec-hash-"}]
    (let [spec (str (fs/path dir "task.spec.edn"))
          recorded {:workflow/spec-path spec :workflow/spec-hash (config/sha256-hex "v1")}]
      (spit spec "v1")
      (is (nil? (workflow/spec-drift recorded)) "unchanged")
      (spit spec "v2")
      (is (= {:workflow/spec-path spec
              :spec/expected-hash (config/sha256-hex "v1")
              :spec/actual-hash (config/sha256-hex "v2")}
             (workflow/spec-drift recorded)))
      (fs/delete spec)
      (is (nil? (:spec/actual-hash (workflow/spec-drift recorded))) "gone: drift, no hash")
      (is (some? (workflow/spec-drift recorded)))
      (is (nil? (workflow/spec-drift {:workflow/id 1})) "nothing recorded: no check"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} the-first-checkpoint-records-the-spec-and-keeps-it
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (fs/with-temp-dir [dir {:prefix "spec-hash-"}]
       (let [spec (str (fs/path dir "task.spec.edn"))
             _ (spit spec "{:spec/title \"v1\"}")
             ctx (spec-run root spec)]
         (store/persist-execution-state! ctx)
         (testing "path and start-time hash are recorded"
           (is (= spec (:workflow/spec-path (manifest root ctx))))
           (is (= (config/sha256-hex "{:spec/title \"v1\"}")
                  (:workflow/spec-hash (manifest root ctx)))))
         (spit spec "{:spec/title \"v2\"}")
         (store/persist-execution-state! (assoc ctx :execution/redirect-count 1))
         (testing "a later checkpoint keeps the start-time hash"
           (is (= (config/sha256-hex "{:spec/title \"v1\"}")
                  (:workflow/spec-hash (manifest root ctx))))))))))

(deftest ^{:stratum 1} the-digest-taken-before-a-move-is-recorded
  ;; The CLI moves a work spec to in-progress before the run starts; the
  ;; file at :spec/path is gone by the first checkpoint.
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (fs/with-temp-dir [dir {:prefix "spec-hash-"}]
       (let [moved (str (fs/path dir "in-progress" "task.spec.edn"))
             ctx (context/create-context {:workflow/id :spec-hash :workflow/version "1.0.0"
                                          :workflow/pipeline [{:phase :plan}]}
                                         {:spec/path (str (fs/path dir "task.spec.edn"))
                                          :spec/location moved
                                          :spec/hash (config/sha256-hex "v1")}
                                         {:checkpoint/root root})]
         (store/persist-execution-state! ctx)
         (is (= moved (:workflow/spec-path (manifest root ctx))))
         (is (= (config/sha256-hex "v1") (:workflow/spec-hash (manifest root ctx)))))))))

(deftest ^{:stratum 1} a-new-attempt-carries-the-source-runs-spec
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (let [carried {:workflow/spec-path "/elsewhere/task.spec.edn"
                    :workflow/spec-hash (config/sha256-hex "original")}
           ctx (context/create-context {:workflow/id :spec-hash :workflow/version "1.0.0"
                                        :workflow/pipeline [{:phase :plan}]}
                                       {:spec/path "task.spec.edn"}
                                       {:checkpoint/root root :checkpoint/spec carried})]
       (store/persist-execution-state! ctx)
       (is (= carried (select-keys (manifest root ctx)
                                   [:workflow/spec-path :workflow/spec-hash])))))))

(deftest ^{:stratum 1} the-recorded-path-follows-the-spec
  (fixture/call-with-temp-checkpoint-root
   (fn [root]
     (fs/with-temp-dir [dir {:prefix "spec-hash-"}]
       (let [spec (str (fs/path dir "task.spec.edn"))
             _ (spit spec "v1")
             ctx (spec-run root spec)
             failed (str (fs/path dir "failed" "task.spec.edn"))]
         (store/persist-execution-state! ctx)
         (workflow/record-spec-path! (:execution/id ctx) failed {:checkpoint/root root})
         (is (= failed (:workflow/spec-path (manifest root ctx))))
         (is (= (config/sha256-hex "v1") (:workflow/spec-hash (manifest root ctx)))))))))

(comment ::checkpoint-spec-hash)
