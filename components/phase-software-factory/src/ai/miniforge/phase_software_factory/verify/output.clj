;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-software-factory.verify.output
  "Parse test output and build bounded verification summaries."
  (:require [ai.miniforge.phase-software-factory.verify.output-config :as config]
            [ai.miniforge.phase-software-factory.verify.failure-block :as block]
            [ai.miniforge.phase-software-factory.verify.result :as result]
            [ai.miniforge.phase-software-factory.messages :as messages]
            [clojure.string :as str]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} sum-groups
  "Sum capture group `group` over every match of `pattern` in `output`."
  [pattern group output]
  (reduce + 0 (map #(parse-long (nth % group)) (re-seq pattern output))))

(defn- ^{:stratum 0} synthesized-error-count
  "The parsed error count, or one phantom error when every parsed count is
   clean yet the runner exited non-zero (a brick that failed to compile
   leaves no summary line). Counted failures are never topped up."
  [fail-count error-count exit-code]
  (if (and (zero? fail-count) (zero? error-count) (not (zero? exit-code)))
    1
    error-count))

(defn- ^{:stratum 0} failing-test-list
  "Comma-separated failing test names, at most `config/max-named-failures`, with
   the remainder counted."
  [names]
  (let [shown (take config/max-named-failures names)
        extra (- (count names) (count shown))
        listed (str/join ", " shown)]
    (if (pos? extra)
      (messages/t :verify/more-failing-tests {:tests listed
                                            :count extra})
      listed)))

(defn- ^{:stratum 0} bounded-output-preview
  "Return a bounded, trimmed preview of test runner output."
  [output]
  (let [trimmed (str/trim (str output))]
    (cond-> (subs trimmed 0 (min (count trimmed) config/verify-error-preview-limit))
      (> (count trimmed) config/verify-error-preview-limit)
      (str config/truncated-output-suffix))))

(defn- ^{:stratum 0} failure-blocks
  "Every `FAIL in` / `ERROR in` block in clojure.test output, in order,
   capped at `config/max-failure-blocks`. Names the failing tests so a consumer
   three namespaces into a 6,000-line run is not hidden behind the first
   namespace's clean summary."
  [output]
  (let [lines (vec (str/split-lines (str output)))]
    (->> (keep-indexed block/failure-header-index lines)
         (take config/max-failure-blocks)
         (mapv #(block/failure-block (subvec lines %))))))

(defn- ^{:stratum 0} parse-no-tests [output exit-code]
  (assoc (result/summary (zero? exit-code) 0 0 0 (if (zero? exit-code) 0 1) output)
         :no-tests? true))

(defn- ^{:stratum 0} parse-cargo
  "Sum every test binary's `test result:` summary; `cargo test --workspace`
   prints one per binary, and the first is often a library with no unit tests."
  [output exit-code]
  (let [counts (for [[_ _ passed-text failed-text]
                     (re-seq #"test result: (\w+)\. (\d+) passed; (\d+) failed" output)]
                 [(parse-long passed-text) (parse-long failed-text)])
        passed (reduce + 0 (map first counts))
        failed (reduce + 0 (map second counts))
        tests (+ passed failed)]
    (result/summary (zero? exit-code) tests tests failed 0 output)))

(defn- ^{:stratum 0} clojure-summary? [output]
  (and (some? (re-find config/ran-tests-pattern output))
       (some? (re-find config/namespace-summary-pattern output))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} parse-clojure [output exit-code]
  (let [failures (sum-groups config/namespace-summary-pattern 1 output)
        errors (sum-groups config/namespace-summary-pattern 2 output)
        passed? (and (zero? failures) (zero? errors) (zero? exit-code))
        blocks (if passed? [] (failure-blocks output))
        tests (sum-groups config/ran-tests-pattern 1 output)
        assertions (sum-groups config/ran-tests-pattern 2 output)
        checked-errors (synthesized-error-count failures errors exit-code)]
    (assoc (result/summary passed? tests assertions failures checked-errors output) :failures blocks)))

(defn- ^{:stratum 1} parse-error-message [results]
  (let [preview (not-empty (bounded-output-preview (:output results)))]
    (str (messages/t :verify/output-unparseable) (when preview (str "\n" preview)))))

(defn- ^{:stratum 1} failure-count-message [results failures errors]
  (let [names (map :test (:failures results))
        counts (messages/t :verify/tests-failed {:fail-count failures
                                               :error-count errors})]
    (cond-> counts
      (seq names) (str " " (messages/t :verify/failing-tests {:tests (failing-test-list names)})))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} parse-test-output [output exit-code]
  (cond
    (re-find #"(?i)No changed bricks|nothing to test" output) (parse-no-tests output exit-code)
    (re-find #"test result:" output) (parse-cargo output exit-code)
    (clojure-summary? output) (parse-clojure output exit-code)
    :else (assoc (result/test-error-result output) :parse-error? true)))

(defn ^{:stratum 2} verify-failure-message [results failures errors]
  (cond
    (:timed-out? results) (str (:output results))
    (:parse-error? results) (parse-error-message results)
    :else (failure-count-message results failures errors)))

(comment ::test-output-language)
