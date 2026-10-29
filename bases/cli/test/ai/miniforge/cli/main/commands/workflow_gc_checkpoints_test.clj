;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.cli.main.commands.workflow-gc-checkpoints-test
  "`mf workflow gc-checkpoints`: passes the age through, reports what was
   deleted and kept, and exits 1 on a bad age or a failed delete."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.cli.main :as main]
            [ai.miniforge.cli.main.commands.shared :as shared]
            [ai.miniforge.cli.main.commands.workflow-commands :as sut]
            [ai.miniforge.workflow.interface :as workflow]
            [clojure.string :as str]
            [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} run-cmd
  "Run the command over dispatch map `m` with GC returning `result`.
   Returns {:gc-opts :exit :out}."
  [m result]
  (let [seen (atom {})
        out (with-redefs [workflow/gc-checkpoints! (fn [opts] (swap! seen assoc :gc-opts opts) result)
                          shared/exit! (fn [code] (swap! seen assoc :exit code))]
              (with-out-str (sut/workflow-gc-checkpoints-cmd m)))]
    (assoc @seen :out out)))

(deftest ^{:stratum 0} a-misspelt-option-is-refused-before-any-gc
  (let [called? (atom false)
        exit (atom nil)
        err (java.io.StringWriter.)]
    (binding [*err* err]
      (with-out-str
        (with-redefs [workflow/gc-checkpoints! (fn [_] (reset! called? true) {})
                      shared/exit! (fn [code] (reset! exit code) nil)]
          (main/-main "workflow" "gc-checkpoints" "--max-age-day" "365"))))
    (is (= 2 @exit))
    (is (false? @called?) "a typo must not run GC at the default age")
    (is (str/includes? (str err) "--max-age-day"))))

;------------------------------------------------------------------------------ Layer 1

(deftest ^{:stratum 1} gc-checkpoints-reports-deleted-and-kept
  (let [{:keys [gc-opts exit out]}
        (run-cmd {:opts {:max-age-days 7}}
                 {:checkpoint/root "/tmp/checkpoints"
                  :deleted [(random-uuid) (random-uuid)]
                  :kept {:pinned [(random-uuid)] :recent [(random-uuid)]}
                  :failures []})]
    (is (= {:max-age-days 7} gc-opts))
    (is (nil? exit))
    (is (str/includes? out "deleted 2, kept 2"))))

(deftest ^{:stratum 1} gc-checkpoints-uses-the-default-age-when-none-given
  (is (= {} (:gc-opts (run-cmd {:opts {}} {:deleted [] :kept {} :failures []})))))

(deftest ^{:stratum 1} gc-checkpoints-exits-1-on-a-bad-age-or-a-failed-delete
  (testing "a negative age deletes nothing"
    (let [{:keys [gc-opts exit]} (run-cmd {:opts {:max-age-days -1}} {})]
      (is (nil? gc-opts))
      (is (= 1 exit))))
  (testing "a delete that failed"
    (is (= 1 (:exit (run-cmd {:opts {}}
                             {:deleted [] :kept {}
                              :failures [(anomaly/anomaly :unavailable "Could not delete" {})]}))))))

(comment ::workflow-gc-checkpoints)
