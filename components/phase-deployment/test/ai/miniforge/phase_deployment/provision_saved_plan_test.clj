;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.provision-saved-plan-test
  "Provision applies only the plan its gated preview saved: the preview runs
   with --save-plan, the apply with --plan, and with no saved plan nothing
   is applied. The plan and its temp directory last only while the gates are
   pending; every terminal outcome removes them. The shell is stubbed; no
   pulumi process runs."
  (:require [ai.miniforge.phase-deployment.provision :as provision]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.phase-deployment.shell.exec :as exec]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ctx
  {:execution/input {:stack-dir "/stacks/infra" :stack "prod"}})

(defn- ^{:stratum 0} after
  "The argument following `flag` in a pulumi argument vector."
  [args flag]
  (second (drop-while #(not= flag %) args)))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stub-shell
  "Record each pulumi argument vector. A preview saves its plan when
   `save-plan?`, as pulumi does, and fails when `preview-fails?`."
  [calls {:keys [save-plan? preview-fails?]} _cmd args & _opts]
  (swap! calls conj args)
  (when-let [plan (and save-plan? (= "preview" (first args)) (after args "--save-plan"))]
    (spit plan "{\"resourcePlans\":{}}"))
  (merge {:success? true :stdout "{}" :stderr "" :exit-code 0 :duration-ms 1
          :command (str "pulumi " (first args))}
         (when (and preview-fails? (= "preview" (first args)))
           {:success? false :stdout "" :stderr "preview failed" :exit-code 1})))

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} provision!
  "Run provision :enter, then :leave unless the preview failed, with the
   shell stubbed. `deny?` marks the gate decision denied in between. Returns
   the final context, the pulumi argument vectors in order, and the directory
   the saved plan was given."
  [{:keys [deny?] :as shell-opts}]
  (let [calls (atom [])]
    (with-redefs [exec/sh-with-timeout (partial stub-shell calls shell-opts)]
      (let [entered (provision/enter-provision ctx)
            pending? (= :pending-gates (get-in entered [:phase :status]))
            plan-dir (some-> (after (first @calls) "--save-plan") io/file .getParentFile)
            held? (and pending? (.isDirectory plan-dir))
            gated (cond-> entered deny? (assoc-in [:phase :phase/status] :failed))
            final (if pending? (provision/leave-provision gated) entered)]
        {:final final :calls @calls :plan-dir plan-dir :held? held?}))))

;------------------------------------------------------------------------------ Layer 3

(deftest ^{:stratum 3} the-apply-is-bound-to-the-saved-plan
  (let [{:keys [final calls plan-dir held?]} (provision! {:save-plan? true})
        [preview up] calls
        plan (after preview "--save-plan")]
    (is held? "the plan is kept while the gates are pending")
    (is (= "up" (first up)))
    (is (= plan (after up "--plan")) "up applies the plan the preview saved")
    (is (some #{"--yes"} up))
    (is (= :completed (get-in final [:phase :status])))
    (is (not (.exists plan-dir)) "the plan and its directory are gone after the apply")))

(deftest ^{:stratum 3} without-a-saved-plan-nothing-is-applied
  (let [{:keys [final calls plan-dir]} (provision! {:save-plan? false})]
    (is (= ["preview"] (mapv first calls)) "pulumi up never ran")
    (is (= :failed (get-in final [:phase :status])))
    (is (= :plan-missing (get-in final [:phase :result :error-type])))
    (is (not (.exists plan-dir))))
  (let [calls (atom [])]
    (with-redefs [exec/sh-with-timeout (partial stub-shell calls {})]
      (is (= :plan-missing (:error-type (shell/pulumi-up! "/stacks/infra" :stack "prod"))))
      (is (empty? @calls) "no plan file given: pulumi up never ran"))))

(deftest ^{:stratum 3} a-failed-preview-removes-its-plan
  (let [{:keys [final calls plan-dir]} (provision! {:save-plan? true :preview-fails? true})]
    (is (= ["preview"] (mapv first calls)))
    (is (= :failed (get-in final [:phase :status])))
    (is (not (.exists plan-dir)) "the partial plan and its directory are gone")))

(deftest ^{:stratum 3} a-denied-gate-removes-the-plan-without-applying
  (let [{:keys [final calls plan-dir held?]} (provision! {:save-plan? true :deny? true})]
    (is held?)
    (is (= ["preview"] (mapv first calls)) "pulumi up never ran")
    (is (= :failed (get-in final [:phase :status])))
    (is (not (.exists plan-dir)))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-deployment.provision-saved-plan-test))
