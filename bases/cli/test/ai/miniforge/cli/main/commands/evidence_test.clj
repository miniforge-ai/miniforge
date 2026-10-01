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
(ns ai.miniforge.cli.main.commands.evidence-test
  "Unit tests for evidence bundle CLI commands."
  (:require
   [clojure.test :refer [deftest testing is use-fixtures]]
   [clojure.edn :as edn]
   [babashka.fs :as fs]
   [ai.miniforge.cli.app-config :as app-config]
   [ai.miniforge.cli.messages :as messages]
   [ai.miniforge.cli.main :as main]
   [ai.miniforge.cli.main.commands.evidence :as sut]
   [ai.miniforge.cli.main.commands.evidence.bundles :as bundles]
   [ai.miniforge.cli.main.commands.evidence.formats :as formats]
   [ai.miniforge.cli.main.commands.evidence-fixtures :as f]
   [ai.miniforge.cli.main.commands.shared :as shared]
   [slingshot.slingshot :refer [try+]]))

;------------------------------------------------------------------------------ Layer 0

;; Fixtures & factories
(defn- ^{:stratum 0} export-options [format path]
  {:id "provider" :format format :output-path path})

(def ^{:stratum 0} ^:dynamic *tmp-dir* nil)

(defn ^{:stratum 0} tmp-dir-fixture [f]
  (let [dir (str (fs/create-temp-dir {:prefix "evidence-test-"}))]
    (binding [*tmp-dir* dir]
      (try+
        (f)
        (finally
          (fs/delete-tree dir))))))

(defn- ^{:stratum 0} make-artifact
  "Factory for a single bundle artifact entry. Defaults to a code artifact;
   override `:artifact/type` or `:artifact/id` to vary per test."
  [& {:as overrides}]
  (merge {:artifact/type :code
          :artifact/id   "art-1"}
         overrides))

(defn- ^{:stratum 0} make-outcome
  "Factory for an `:evidence/outcome` value."
  [& {:as overrides}]
  (merge {:outcome/success false :outcome/tier :standard}
         overrides))

(defn- ^{:stratum 0} make-dependency-health
  "Factory for a single `:evidence/dependency-health` entry."
  [& {:as overrides}]
  (merge {:dependency/id     :anthropic
          :dependency/status :degraded}
         overrides))

(defn- ^{:stratum 0} make-failure-attribution
  "Factory for an `:evidence/failure-attribution` map."
  [& {:as overrides}]
  (merge {:failure/source   :external-provider
          :failure/vendor   :anthropic
          :dependency/id    :anthropic
          :dependency/class :rate-limit}
         overrides))

(defn- ^{:stratum 0} make-list-bundle
  "Factory for the minimal bundle shape returned by the optional provider in list output."
  [& {:as overrides}]
  (merge {:bundle/id          "b-1"
          :bundle/workflow-id "wf-1"
          :bundle/status      "complete"}
         overrides))

(deftest ^{:stratum 0} evidence-show-cmd-missing-id-test
  (testing "show command exits with error when no id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/evidence-show-cmd {}))
        (is @exited?)))))

(deftest ^{:stratum 0} evidence-export-cmd-missing-id-test
  (testing "export command exits with error when no id provided"
    (let [exited? (atom false)]
      (with-redefs [shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/evidence-export-cmd {}))
        (is @exited?)))))

;; Tests
(deftest ^{:stratum 0} detail-defaults-follow-the-current-catalog
  (let [catalog (assoc (messages/catalog) :evidence/unknown-value "inconnu"
                                         :evidence/missing-value "absent")]
    (with-redefs [messages/catalog (constantly catalog)]
      (let [fields (:fields (bundles/bundle-detail-spec))]
        (is (= "absent" (get-in fields [0 2 :default])))
        (is (= "inconnu" (get-in fields [1 2 :default])))))))

;------------------------------------------------------------------------------ Layer 1

(defn- ^{:stratum 1} make-bundle
  "Factory for a minimal legacy evidence bundle. Pass overrides as kwargs."
  [& {:as overrides}]
  (merge {:bundle/id          "bundle-1"
          :bundle/workflow-id "wf-1"
          :bundle/status      "complete"
          :bundle/created-at  "2026-04-13T10:00:00Z"
          :bundle/artifacts   [(make-artifact)]
          :bundle/phases      [:plan :implement]}
         overrides))

(defn- ^{:stratum 1} make-canonical-bundle []
  (f/bundle {:evidence/outcome (make-outcome)
             :evidence/dependency-health {:anthropic (make-dependency-health)}
             :evidence/failure-attribution (make-failure-attribution)}))

(deftest ^{:stratum 1} evidence-list-cmd-no-component-empty-dir-test
  (testing "list command shows 'no bundles' when dir is empty"
    (with-redefs [shared/call-optional-provider (constantly nil)
                  app-config/home-dir (constantly *tmp-dir*)]
      (let [output (with-out-str (sut/evidence-list-cmd {}))]
        (is (re-find #"(?i)no evidence" output)))
      (let [directory (str *tmp-dir* "/evidence")]
        (fs/create-dirs directory)
        (doseq [name ["export.json" "export.html" "notes.txt"]]
          (spit (str directory "/" name) "export"))
        (is (re-find #"(?i)no evidence" (with-out-str (sut/evidence-list-cmd {}))))))))

(deftest ^{:stratum 1} evidence-list-cmd-component-results-test
  (testing "list command displays component results when available"
    (with-redefs [shared/call-optional-provider
                  (constantly [(make-list-bundle)])]
      (let [output (with-out-str (sut/evidence-list-cmd {}))]
        (is (.contains output "b-1"))))))

(deftest ^{:stratum 1} evidence-show-cmd-not-found-test
  (testing "show command reports not found for unknown bundle"
    (let [exited? (atom false)]
      (with-redefs [shared/call-optional-provider (constantly nil)
                    app-config/home-dir (constantly *tmp-dir*)
                    shared/exit! (fn [_] (reset! exited? true))]
        (with-out-str (sut/evidence-show-cmd {:id "missing"}))
        (is @exited?)))))

(deftest ^{:stratum 1} load-bundle-from-file-test
  (testing "loads valid EDN file"
    (let [f (java.io.File. (str *tmp-dir* "/test.edn"))]
      (spit f (pr-str {:bundle/id "b-1"}))
      (is (= {:bundle/id "b-1"} (bundles/load-bundle-from-file f)))))

  (testing "returns nil for non-EDN file"
    (let [f (java.io.File. (str *tmp-dir* "/test.json"))]
      (spit f "{}")
      (is (nil? (bundles/load-bundle-from-file f))))))

(deftest ^{:stratum 1} file-loader-rejects-trailing-and-oversized-edn-test
  (let [file (java.io.File. (str *tmp-dir* "/bounded.edn"))
        over-limit (inc (* 16 1024 1024))]
    (spit file "{} {}")
    (is (nil? (bundles/load-bundle-from-file file)))
    (with-open [output (java.io.RandomAccessFile. file "rw")]
      (.setLength output over-limit))
    (is (nil? (bundles/load-bundle-from-file file)))))

(deftest ^{:stratum 1} list-reports-canonical-status-and-rejected-filenames-test
  (doseq [[success status] [[true "completed"] [false "failed"]]]
    (let [bundle (f/bundle {:evidence/outcome (make-outcome :outcome/success success)})]
      (with-redefs [shared/call-optional-provider (constantly [bundle])]
        (is (.contains (with-out-str (sut/evidence-list-cmd {})) status)))))
  (let [directory (str *tmp-dir* "/evidence")]
    (fs/create-dirs directory)
    (spit (str directory "/malformed.edn") "{")
    (spit (str directory "/missing-id.edn") "{}")
    (with-redefs [shared/call-optional-provider (constantly nil)
                  app-config/home-dir (constantly *tmp-dir*)]
      (let [output (with-out-str (sut/evidence-list-cmd {}))]
        (is (.contains output "malformed.edn"))
        (is (.contains output "missing-id.edn"))
        (is (.contains output "refused"))))))

(deftest ^{:stratum 1} malformed-files-are-refused-rather-than-reported-missing
  (let [directory (str *tmp-dir* "/evidence")
        source (str directory "/malformed.edn")
        destination (str directory "/malformed-export.edn")]
    (fs/create-dirs directory)
    (with-redefs [shared/call-optional-provider (constantly nil)
                  app-config/home-dir (constantly *tmp-dir*)
                  shared/exit! identity]
      (doseq [text ["{" "{} {}" "nil" "false"]]
        (spit source text)
        (spit destination "unchanged")
        (is (.contains (with-out-str (sut/evidence-show-cmd {:id "malformed"})) "refused"))
        (is (.contains (with-out-str (sut/evidence-export-cmd {:id "malformed"})) "refused"))
        (is (= "unchanged" (slurp destination)))))))

(deftest ^{:stratum 1} falsey-legacy-fields-preserve-canonical-presentation
  (doseq [legacy [nil false] [success status] [[true "completed"] [false "failed"]]]
    (let [bundle (f/bundle {:bundle/status legacy :bundle/artifacts legacy :bundle/phases legacy
                            :evidence/outcome (make-outcome :outcome/success success)
                            :evidence/failure-attribution
                            (make-failure-attribution :failure/source legacy :dependency/source :external-provider
                                                      :dependency/class legacy :failure/class :rate-limit)})]
      (with-redefs [shared/call-optional-provider (constantly bundle)]
        (let [output (with-out-str (sut/evidence-show-cmd {:id "canonical-bundle"}))]
          (is (.contains output status))
          (is (.contains output "external-provider / anthropic / rate-limit")))))))

(deftest ^{:stratum 1} export-formats-honor-the-requested-destination
  (let [bundle (f/bundle {:test/text "café 東京"})]
    (with-redefs [shared/call-optional-provider (constantly bundle)]
      (doseq [format ["edn" "json" "html"]]
        (doseq [destination [(str *tmp-dir* "/requested/audit." format)
                             (str "audit-" (random-uuid) "." format)]]
          (try+
            (with-out-str (sut/evidence-export-cmd (export-options format destination)))
            (is (= (formats/encode bundle format) (slurp destination :encoding "UTF-8")))
            (finally (fs/delete-if-exists destination))))))))

(deftest ^{:stratum 1} cli-parser-keeps-destination-separate-from-format
  (let [bundle (f/bundle {:test/text "café 東京"})]
    (with-redefs [shared/call-optional-provider (constantly bundle)]
      (doseq [[format args] [["edn" []] ["json" ["--format" "json"]] ["html" ["--format" "html"]]]]
        (let [destination (str *tmp-dir* "/requested by CLI/audit." format)]
          (with-out-str (apply main/-main "evidence" "export" "provider" destination args))
          (is (= (formats/encode bundle format) (slurp destination :encoding "UTF-8"))))))))

(deftest ^{:stratum 1} renderer-failure-does-not-write-or-create-directories
  (let [bundle (f/bundle {})
        destination (str *tmp-dir* "/unchanged.edn")
        absent (str *tmp-dir* "/not-created/audit.json")]
    (spit destination "unchanged")
    (with-redefs [shared/call-optional-provider (constantly bundle)
                  formats/encode (constantly nil)
                  shared/exit! identity]
      (doseq [path [destination absent]]
        (is (.contains (with-out-str (sut/evidence-export-cmd (export-options "json" path)))
                       (messages/t :evidence/export-failed {:path path}))))
      (is (= "unchanged" (slurp destination)))
      (is (not (fs/exists? (str *tmp-dir* "/not-created")))))))

;------------------------------------------------------------------------------ Layer 2

(deftest ^{:stratum 2} evidence-show-cmd-with-bundle-test
  (testing "show command displays bundle details from filesystem"
    (let [evidence-path (str *tmp-dir* "/evidence")]
      (fs/create-dirs evidence-path)
      (spit (str evidence-path "/test-bundle.edn") (pr-str (make-bundle)))
      (with-redefs [shared/call-optional-provider (constantly nil)
                    app-config/home-dir (constantly *tmp-dir*)
                    shared/exit! identity]
        (let [output (with-out-str (sut/evidence-show-cmd {:id "test-bundle"}))]
          (is (.contains output "unsealed"))
          (is (not (.contains output "wf-1"))))))))

(deftest ^{:stratum 2} evidence-show-cmd-with-canonical-bundle-test
  (testing "show command normalizes canonical evidence bundle fields"
    (let [evidence-path (str *tmp-dir* "/evidence")]
      (fs/create-dirs evidence-path)
      (spit (str evidence-path "/canonical-bundle.edn") (pr-str (make-canonical-bundle)))
      (with-redefs [shared/call-optional-provider (constantly nil)
                    app-config/home-dir (constantly *tmp-dir*)]
        (let [output (with-out-str (sut/evidence-show-cmd {:id "canonical-bundle"}))]
          (is (.contains output "00000000-0000-0000-0000-000000000002"))
          (is (.contains output "failed"))
          (is (.contains output "external-provider / anthropic / rate-limit"))
          (is (.contains output "Dependencies: 1")))))))

(deftest ^{:stratum 2} evidence-boundaries-reject-tampering-and-preserve-export-destination
  (let [directory (str *tmp-dir* "/evidence")
        source (str directory "/checked.edn")
        destination (str directory "/checked-export.edn")
        sealed (make-canonical-bundle)]
    (fs/create-dirs directory)
    (with-redefs [shared/call-optional-provider (constantly nil)
                  app-config/home-dir (constantly *tmp-dir*)
                  shared/exit! identity]
      (spit source (pr-str sealed))
      (with-out-str (sut/evidence-export-cmd {:id "checked"}))
      (is (= sealed (edn/read-string (slurp destination))))
      (doseq [invalid [(assoc-in sealed [:evidence/outcome :outcome/success] true)
                       (f/bundle {:test/text "AKIAIOSFODNN7EXAMPLE"})
                       (dissoc sealed :evidence/content-hash :evidence/sealed-at)]]
        (spit source (pr-str invalid))
        (spit destination "unchanged")
        (is (.contains (with-out-str (sut/evidence-show-cmd {:id "checked"})) "refused"))
        (is (.contains (with-out-str (sut/evidence-list-cmd {})) "refused"))
        (is (.contains (with-out-str (sut/evidence-export-cmd {:id "checked"})) "refused"))
        (is (= "unchanged" (slurp destination))))
      (spit source (pr-str sealed))
      (is (.contains (with-out-str (sut/evidence-export-cmd {:id "checked" :format "unknown"})) "Unsupported"))
      (is (not (fs/exists? (str directory "/checked-export.unknown")))))))

(deftest ^{:stratum 2} provider-only-bundle-exports-the-validated-snapshot-test
  (let [bundle (make-canonical-bundle)
        destination (str *tmp-dir* "/evidence/provider-export.edn")]
    (with-redefs [shared/call-optional-provider (constantly bundle)
                  app-config/home-dir (constantly *tmp-dir*)]
      (with-out-str (sut/evidence-export-cmd {:id "provider"}))
      (is (= bundle (edn/read-string (slurp destination)))))
    (with-redefs [shared/call-optional-provider (constantly (assoc bundle :evidence/content-hash "wrong"))
                  app-config/home-dir (constantly *tmp-dir*)
                  shared/exit! identity]
      (spit destination "unchanged")
      (is (.contains (with-out-str (sut/evidence-export-cmd {:id "provider"})) "refused"))
      (is (= "unchanged" (slurp destination))))))

(use-fixtures :each tmp-dir-fixture)
