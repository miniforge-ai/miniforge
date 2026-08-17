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
(ns ai.miniforge.phase-deployment.shell.kustomize-test
  (:require
   [ai.miniforge.phase-deployment.shell.exec :as exec]
   [ai.miniforge.phase-deployment.shell.kustomize :as kustomize]
   [ai.miniforge.schema.interface :as schema]
   [clojure.test :refer [deftest is testing]]))

;------------------------------------------------------------------------------ Layer 0

(def ^{:stratum 0} manifest-bytes "apiVersion: apps/v1\nkind: Deployment\n")

(defn ^{:stratum 0} blank-stderr-failure
  [message]
  (schema/failure :stdout message {:stderr ""}))

(defn ^{:stratum 0} recording-shell
  [calls]
  (fn [command args & {:as options}]
    (swap! calls conj {:command command :args args :options options})
    (schema/success :stdout "accepted")))

;------------------------------------------------------------------------------ Layer 1

(defn ^{:stratum 1} scripted-shell
  "Stub the process boundary and nothing above it. `kustomize-build!`,
   `kustomize-render!`, and `kustomize-apply!` all stay real, so what these
   tests assert is the producers' own output shape rather than a stub's."
  [calls & {:keys [apply-result]
            :or {apply-result (schema/success :stdout "deployment.apps/api configured")}}]
  (fn [command args & {:as options}]
    (swap! calls conj {:command command :args args :options options})
    (if (= "kustomize" command)
      (schema/success :stdout manifest-bytes)
      apply-result)))

(deftest ^{:stratum 1} server-dry-run-and-apply-use-identical-input-test
  (let [calls (atom [])]
    (with-redefs [exec/sh-with-timeout (recording-shell calls)]
      (kustomize/kubectl-apply! manifest-bytes
                                 :namespace "production"
                                 :context "cluster-1"
                                 :server-dry-run? true)
      (kustomize/kubectl-apply! manifest-bytes
                                 :namespace "production"
                                 :context "cluster-1"))
    (let [[dry-run apply-call] @calls]
      (is (= [manifest-bytes manifest-bytes]
             (mapv #(get-in % [:options :in]) @calls)))
      (is (= ["apply" "-f" "-" "--namespace" "production"
              "--context" "cluster-1" "--dry-run=server" "-o" "yaml"]
             (:args dry-run)))
      (testing "the real mutation cannot inherit the dry-run flag"
        (is (= ["apply" "-f" "-" "--namespace" "production"
                "--context" "cluster-1"]
               (:args apply-call)))))))

(deftest ^{:stratum 1} a-failed-build-renders-no-manifest-test
  (let [result (with-redefs [exec/sh-with-timeout
                             (fn [& _] (blank-stderr-failure "build broke"))]
                 (kustomize/kustomize-render! "/deployment"))]
    (is (schema/failed? result))
    (is (nil? (:rendered-yaml result))
        "a nil manifest means no manifest, not a manifest under another key")))

(deftest ^{:stratum 1} blank-stderr-retains-command-error-test
  (testing "build failures retain the structured command error"
    (let [result (with-redefs [kustomize/kustomize-build!
                               (fn [& _] (blank-stderr-failure "build broke"))]
                   (kustomize/kustomize-apply! "/deployment"))]
      (is (schema/failed? result))
      (is (re-find #"build broke" (:error result)))))
  (testing "apply failures retain the structured command error"
    (let [result (with-redefs [kustomize/kustomize-build!
                               (fn [& _] (schema/success :stdout manifest-bytes))
                               exec/sh-with-timeout
                               (fn [& _] (blank-stderr-failure "apply broke"))]
                   (kustomize/kustomize-apply! "/deployment"))]
      (is (schema/failed? result))
      (is (= "apply broke" (:error result))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} render-and-apply-report-the-manifest-under-one-key-test
  ;; The trap this closes: a governed caller wired :render! and then read
  ;; :rendered-yaml off the result. Rendering used to return a raw shell
  ;; result, so that key was nil, and a healthy render read as an empty one
  ;; — which denies every deploy.
  (let [render-calls (atom [])
        rendered (with-redefs [exec/sh-with-timeout (scripted-shell render-calls)]
                   (kustomize/kustomize-render! "/deployment"))
        applied (with-redefs [exec/sh-with-timeout (scripted-shell (atom []))]
                  (kustomize/kustomize-apply! "/deployment"))]
    (is (= manifest-bytes (:rendered-yaml rendered)))
    (is (= (:rendered-yaml rendered) (:rendered-yaml applied))
        "render and apply must report the manifest under the same key")
    (testing "a render contacts no cluster"
      (is (= ["kustomize"] (mapv :command @render-calls)))
      (is (nil? (:apply-result rendered))
          "nothing was applied, so there is no apply result to report"))))

(deftest ^{:stratum 2} a-refused-apply-keeps-the-manifest-it-built-test
  ;; The manifest kubectl rejected is the evidence of what was attempted.
  ;; Nilling it left callers digging it out of [:build-result :stdout] by
  ;; guesswork, which is the same trap wearing a different key.
  (let [result (with-redefs [exec/sh-with-timeout
                             (scripted-shell (atom [])
                                             :apply-result (blank-stderr-failure "apply broke"))]
                 (kustomize/kustomize-apply! "/deployment"))]
    (is (schema/failed? result))
    (is (= manifest-bytes (:rendered-yaml result))
        "the build succeeded, so its manifest survives the apply failure")))
