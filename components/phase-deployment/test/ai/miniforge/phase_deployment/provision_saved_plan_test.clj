;; Title: Miniforge.ai
;; Copyright 2025-2026 Christopher Lester (christopher@miniforge.ai)
;; Licensed under the Apache License, Version 2.0.
(ns ai.miniforge.phase-deployment.provision-saved-plan-test
  "Provision applies only the plan its gated preview saved: the preview runs
   with --save-plan, the apply with --plan, and with no saved plan nothing
   is applied. The shell is stubbed; no pulumi process runs."
  (:require [ai.miniforge.phase-deployment.provision :as provision]
            [ai.miniforge.phase-deployment.shell :as shell]
            [ai.miniforge.phase-deployment.shell.exec :as exec]
            [clojure.java.io :as io]
            [clojure.test :refer [deftest is]])
  (:import (java.nio.file Files)
           (java.nio.file.attribute FileAttribute)))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} ctx
  {:execution/input {:stack-dir "/stacks/infra" :stack "prod"}})

(defn- ^{:stratum 0} after
  "The argument following `flag` in a pulumi argument vector."
  [args flag]
  (second (drop-while #(not= flag %) args)))

(defn- ^{:stratum 0} with-plan-dir
  "Call `f` with a plan path in a fresh temp directory; remove both after."
  [f]
  (let [dir (Files/createTempDirectory "provision-plan-test-" (make-array FileAttribute 0))
        plan (str (.resolve dir "plan.json"))]
    (try (f plan)
         (finally (io/delete-file plan true)
                  (io/delete-file (.toFile dir) true)))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} stub-shell
  "Record each pulumi argument vector; answer with empty JSON. A preview
   saves its plan when `save-plan?`, as pulumi does."
  [calls save-plan? _cmd args & _opts]
  (swap! calls conj args)
  (when-let [plan (and save-plan? (= "preview" (first args)) (after args "--save-plan"))]
    (spit plan "{\"resourcePlans\":{}}"))
  {:success? true :stdout "{}" :stderr "" :exit-code 0 :duration-ms 1
   :command (str "pulumi " (first args))})

;------------------------------------------------------------------------------ Layer 2

(defn- ^{:stratum 2} provision!
  "Run provision :enter then :leave with the shell stubbed. Returns the left
   context and the pulumi argument vectors, in order."
  [plan save-plan?]
  (let [calls (atom [])]
    (with-redefs [exec/sh-with-timeout (partial stub-shell calls save-plan?)
                  provision/new-plan-file (constantly plan)]
      {:left (provision/leave-provision (provision/enter-provision ctx))
       :calls @calls})))

;------------------------------------------------------------------------------ Layer 3

(deftest ^{:stratum 3} the-apply-is-bound-to-the-saved-plan
  (with-plan-dir
    (fn [plan]
      (let [{:keys [left calls]} (provision! plan true)
            [preview up] calls]
        (is (= "preview" (first preview)))
        (is (= plan (after preview "--save-plan")))
        (is (= "up" (first up)))
        (is (= plan (after up "--plan")) "up applies the plan the preview saved")
        (is (some #{"--yes"} up))
        (is (= :completed (get-in left [:phase :status])))))))

(deftest ^{:stratum 3} without-a-saved-plan-nothing-is-applied
  (with-plan-dir
    (fn [plan]
      (let [{:keys [left calls]} (provision! plan false)]
        (is (= ["preview"] (mapv first calls)) "pulumi up never ran")
        (is (= :failed (get-in left [:phase :status])))
        (is (= :plan-missing (get-in left [:phase :result :error-type]))))))
  (let [calls (atom [])]
    (with-redefs [exec/sh-with-timeout (partial stub-shell calls false)]
      (is (= :plan-missing (:error-type (shell/pulumi-up! "/stacks/infra" :stack "prod"))))
      (is (empty? @calls) "no plan file given: pulumi up never ran"))))

(comment
  (clojure.test/run-tests 'ai.miniforge.phase-deployment.provision-saved-plan-test))
