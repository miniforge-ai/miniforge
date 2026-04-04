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

(ns examples.basic-usage
  "Basic usage examples for the evidence-bundle component."
  (:require
   [ai.miniforge.evidence-bundle.interface :as evidence]
   [ai.miniforge.artifact.interface :as artifact]))

;; ============================================================================
;; Example 1: Basic Evidence Bundle Creation
;; ============================================================================

(comment
  ;; Setup
  (def artifact-store (artifact/create-transit-store))
  (def evidence-mgr (evidence/create-evidence-manager
                     {:artifact-store artifact-store}))

  ;; Create workflow state (normally from workflow component)
  (def workflow-id (random-uuid))
  (def workflow-state
    {:workflow/id workflow-id
     :workflow/status :completed
     :workflow/spec {:intent/type :import
                     :title "Import RDS instance"
                     :description "Import existing RDS to Terraform state"
                     :business-reason "Enable infrastructure-as-code management"
                     :constraints [{:constraint/type :no-resource-creation}
                                   {:constraint/type :no-resource-destruction}]}
     :workflow/phases {:plan {:agent :planner
                             :started-at (java.time.Instant/now)
                             :completed-at (java.time.Instant/now)
                             :duration-ms 1500
                             :output {:plan "Import plan"}
                             :artifacts []}
                       :implement {:agent :implementer
                                  :started-at (java.time.Instant/now)
                                  :completed-at (java.time.Instant/now)
                                  :duration-ms 3000
                                  :output {:implementation "Terraform import"}
                                  :artifacts []}}
     :workflow/gate-results []
     :workflow/pr-info {:number 234
                        :url "https://github.com/acme/terraform/pull/234"
                        :status :merged
                        :merged-at (java.time.Instant/now)}})

  ;; Create evidence bundle
  (def bundle (evidence/create-bundle
               evidence-mgr
               workflow-id
               {:workflow-state workflow-state}))

  ;; Inspect bundle
  (println "Bundle ID:" (:evidence-bundle/id bundle))
  (println "Workflow ID:" (:evidence-bundle/workflow-id bundle))
  (println "Intent type:" (get-in bundle [:evidence/intent :intent/type]))
  (println "Outcome success:" (get-in bundle [:evidence/outcome :outcome/success]))

  ;; Retrieve bundle
  (def retrieved (evidence/get-bundle evidence-mgr (:evidence-bundle/id bundle)))
  (println "Retrieved:" (= bundle retrieved))

  ;; Query bundles by criteria
  (def import-bundles (evidence/query-bundles evidence-mgr {:intent-type :import}))
  (println "Import bundles count:" (count import-bundles))

  ;; Export for audit
  (evidence/export-bundle evidence-mgr (:evidence-bundle/id bundle) "/tmp/evidence.edn")
  (println "Exported to /tmp/evidence.edn"))

;; ============================================================================
;; Example 2: Semantic Intent Validation
;; ============================================================================

(comment
  (def artifact-store (artifact/create-transit-store))
  (def evidence-mgr (evidence/create-evidence-manager
                     {:artifact-store artifact-store}))

  ;; Case 1: Valid import (no creates)
  (def intent-import {:intent/type :import})
  (def artifacts-no-changes [])

  (def result-valid (evidence/validate-intent evidence-mgr intent-import artifacts-no-changes))
  (println "Import validation passed:" (:passed? result-valid))
  ;; => true

  ;; Case 2: Invalid import (has creates)
  (def artifacts-with-creates
    [{:artifact/type :terraform-plan
      :artifact/content "aws_instance.web will be created\naws_s3_bucket.data will be created"}])

  (def result-invalid (evidence/validate-intent evidence-mgr intent-import artifacts-with-creates))
  (println "Import validation passed:" (:passed? result-invalid))
  ;; => false
  (println "Violations:" (:violations result-invalid))
  ;; => [{:violation/rule-id "semantic-creates"
  ;;      :violation/severity :critical
  ;;      :violation/message "Intent 'import' expects 0 creates, found 2"}]

  ;; Case 3: Valid create
  (def intent-create {:intent/type :create})
  (def result-create (evidence/validate-intent evidence-mgr intent-create artifacts-with-creates))
  (println "Create validation passed:" (:passed? result-create))
  ;; => true
  (println "Resource creates:" (:semantic-validation/resource-creates result-create))
  ;; => 2
  )

;; ============================================================================
;; Example 3: Provenance Tracing
;; ============================================================================

(comment
  (def artifact-store (artifact/create-transit-store))
  (def evidence-mgr (evidence/create-evidence-manager
                     {:artifact-store artifact-store}))

  ;; Create workflow and bundle (as in Example 1)
  (def workflow-id (random-uuid))
  (def workflow-state {:workflow/id workflow-id
                        :workflow/phase :completed
                        :workflow/spec {:spec/title "Example workflow"}})

  (evidence/create-bundle evidence-mgr workflow-id {:workflow-state workflow-state})

  ;; Trace complete artifact chain
  (def chain (evidence/trace-artifact-chain evidence-mgr workflow-id))

  (println "Original intent:" (get-in chain [:intent :intent/type]))
  (println "Phase chain:")
  (doseq [phase (:chain chain)]
    (println "  Phase:" (:phase phase)
             "| Agent:" (:agent phase)
             "| Artifacts:" (count (:artifacts phase))
             "| Timestamp:" (:timestamp phase)))
  (println "Final outcome:" (:outcome chain))

  ;; Find intent mismatches across all workflows
  (def mismatches (evidence/query-intent-mismatches evidence-mgr {}))
  (println "Intent mismatches found:" (count mismatches))
  (doseq [m mismatches]
    (println "  Workflow:" (:workflow-id m)
             "| Declared:" (:declared-intent m)
             "| Actual:" (:actual-behavior m))))

;; ============================================================================
;; Example 4: Automatic Collection with Workflow Integration
;; ============================================================================

(comment
  (require '[ai.miniforge.evidence-bundle.workflow-integration :as integration]
           '[ai.miniforge.workflow.interface :as workflow])

  (def artifact-store (artifact/create-transit-store))

  ;; Method 1: Manual setup
  (def evidence-mgr (evidence/create-evidence-manager
                     {:artifact-store artifact-store}))
  (def collector (integration/create-evidence-collector
                  {:evidence-manager evidence-mgr
                   :artifact-store artifact-store}))

  (def wf (-> (workflow/create-workflow)
              (integration/attach-to-workflow collector)))

  ;; Method 2: Convenience function
  (def wf2 (-> (workflow/create-workflow)
               (integration/create-and-attach-evidence-collector artifact-store)))

  ;; Now run workflow - evidence bundle created automatically on completion
  (def example-spec {:spec/title "Example workflow"})
  (def example-context {:context/id (random-uuid)})
  (def workflow-id (workflow/start wf2 example-spec example-context))
  (def result (workflow/run-workflow wf2 example-spec example-context))

  ;; Evidence bundle is automatically created
  (def bundle (evidence/get-bundle-by-workflow evidence-mgr workflow-id))
  (println "Auto-created bundle:" (:evidence-bundle/id bundle)))

;; ============================================================================
;; Example 5: Terraform Plan Analysis
;; ============================================================================

(comment
  (def artifact-store (artifact/create-transit-store))
  (def evidence-mgr (evidence/create-evidence-manager
                     {:artifact-store artifact-store}))

  ;; Terraform plan artifact
  (def plan-artifact
    {:artifact/type :terraform-plan
     :artifact/content
     (str "Terraform will perform the following actions:\n"
          "\n"
          "  # aws_instance.web will be created\n"
          "  + resource \"aws_instance\" \"web\" {\n"
          "  ...\n"
          "\n"
          "  # aws_s3_bucket.data will be updated in-place\n"
          "  ~ resource \"aws_s3_bucket\" \"data\" {\n"
          "  ...\n"
          "\n"
          "  # aws_db_instance.old will be destroyed\n"
          "  - resource \"aws_db_instance\" \"old\" {\n"
          "  ...\n"
          "\n"
          "Plan: 1 to add, 1 to change, 1 to destroy.")})

  ;; Analyze plan
  (def analysis (evidence/analyze-terraform-plan evidence-mgr plan-artifact))
  (println "Creates:" (:creates analysis))   ;; => 1
  (println "Updates:" (:updates analysis))   ;; => 1
  (println "Destroys:" (:destroys analysis)) ;; => 1

  ;; Validate against intent
  (def intent {:intent/type :migrate})
  (def result (evidence/validate-intent evidence-mgr intent [plan-artifact]))
  (println "Migrate validation passed:" (:passed? result))
  ;; => true (migrate allows creates and destroys)
  )
