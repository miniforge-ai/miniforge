;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.opsv-terminal-workflow-support
  "Real runner/checkpoint recovery after simulated provider success and audit failure."
  (:require [ai.miniforge.anomaly.interface :as anomaly]
            [ai.miniforge.artifact.interface :as artifact]
            [ai.miniforge.event-stream.interface :as events]
            [ai.miniforge.phase-opsv.artifact-test-support :as material]
            [ai.miniforge.phase-opsv.governance-fixtures :as governance]
            [ai.miniforge.phase-opsv.interface :as opsv]
            [ai.miniforge.phase-opsv.pr-audit :as audit]
            [ai.miniforge.phase-opsv.pr-fixtures :as pr]
            [ai.miniforge.workflow.interface :as workflow]
            [ai.miniforge.workflow.opsv-lifecycle-support :as support]
            [clojure.java.io :as io]
            [clojure.test :refer [is]]))

;------------------------------------------------------------------------------ Layer 0

(defn- ^{:stratum 0} fail-terminal-audit [record ctx transaction]
  (if (= :succeeded (:effect/state transaction))
    (anomaly/anomaly :unavailable "Simulated terminal audit failure" {:effect/transaction transaction})
    (record ctx transaction)))

(defn- ^{:stratum 0} prepare-and-actuate [actuate ctx]
  ;; Test-owned host preparation binds the fixed simulated diff to this run's policy.
  ;; Production host preparation is a separate implementation slice.
  (let [hash (get-in ctx [:execution/phase-results :opsv/verify :result :output :opsv/policy-hash])]
    (actuate (assoc-in ctx [:execution/opts :opsv/pr-execution :target :opsv/policy-hash] hash))))

(defn- ^{:stratum 0} material-input []
  (-> (support/workflow-input)
      (dissoc :opsv/evidence-refs :opsv/metric-snapshot-artifact-refs :opsv/policy-diff-artifact-refs)
      (assoc-in [:opsv/experiment-pack :experiment-pack/actuation-intent] :pr-only)))

(defn- ^{:stratum 0} run-options [root runtime]
  (let [id (random-uuid)
        stream (events/create-event-stream {:sinks []})
        base (get-in (material/configured {:execution/id id}) [:execution/opts :opsv/evidence-base])]
    (assoc (support/workflow-options stream root)
           :workflow-id id :opsv/artifact-directory (.getCanonicalPath (io/file root))
           :opsv/evidence-base base :opsv/governance governance/policy :opsv/pr-execution runtime)))

(defn- ^{:stratum 0} retained-output [phase]
  (get-in phase [:result :output :anomaly/data :opsv/phase-output]))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} run-with-audit-failure [options]
  (with-redefs [audit/record! (partial fail-terminal-audit audit/record!)
                opsv/actuate (partial prepare-and-actuate opsv/actuate)]
    (let [config (:workflow (workflow/load-workflow :opsv "1.0.0" {:skip-cache? true}))]
      (workflow/run-pipeline config (material-input) options))))

(defn- ^{:stratum 1} confirm-recovery! [root options completed calls]
  (let [saved (workflow/load-checkpoint-data (:workflow-id options) {:checkpoint/root root})
        snapshot (:machine-snapshot saved)
        encoded (get-in snapshot [:execution/input :opsv/terminal-snapshot])
        retained (retained-output (:artifact/content (artifact/decode-snapshot encoded)))
        restored (assoc snapshot :execution/opts (dissoc options :opsv/pr-execution))
        recovered (opsv/recover-actuation-evidence! restored)
        output (retained-output (:phase recovered))
        bundle (:opsv/evidence-bundle output)
        again (opsv/recover-actuation-evidence! (dissoc recovered :opsv/evidence-assembly-store))]
    (is (= :failed (:execution/status completed)))
    (is (nil? (:phase snapshot)))
    (is (= :succeeded (get-in retained [:opsv/effect-transactions 0 :effect/state])))
    (is (= (:opsv/effect-transactions retained) (:opsv/effect-transactions output)))
    (is (= :error (get-in recovered [:phase :result :status])))
    (is (false? (get-in bundle [:evidence/outcome :outcome/success])))
    (is (= ["https://github.com/example/opsv/pull/17"]
           (get-in bundle [:evidence/opsv :opsv/actuation :pr-refs])))
    (is (= bundle (:artifact/content (artifact/read-published
                                      (:opsv/artifact-directory options)
                                      (:opsv/evidence-artifact-id output)))))
    (is (= output (retained-output (:phase again))))
    (is (= ["GET" "POST"] (mapv #(get-in % [:arguments 6]) @calls)))))

;------------------------------------------------------------------------------ Layer 2

(defn ^{:stratum 2} run-and-recover! [root]
  (let [{:keys [runtime calls]} (pr/setup)
        options (run-options root runtime)]
    (try
      (confirm-recovery! root options (run-with-audit-failure options) calls)
      (finally
        (doseq [file (reverse (file-seq (io/file (get-in runtime [:provider :directory]))))]
          (io/delete-file file))))))

(comment
  (retained-output {}))
