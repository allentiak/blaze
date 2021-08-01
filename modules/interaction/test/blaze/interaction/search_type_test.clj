(ns blaze.interaction.search-type-test
  "Specifications relevant for the FHIR search interaction:

  https://www.hl7.org/fhir/http.html#search"
  (:require
    [blaze.db.api-stub :refer [mem-node-with]]
    [blaze.fhir.spec.type :as type]
    [blaze.interaction.search-type]
    [blaze.interaction.search-type-spec]
    [blaze.interaction.search.nav-spec]
    [blaze.interaction.search.params-spec]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [deftest is testing]]
    [integrant.core :as ig]
    [juxt.iota :refer [given]]
    [reitit.core :as reitit]
    [taoensso.timbre :as log])
  (:import
    [java.time Instant]))


(st/instrument)
(log/set-level! :trace)


(defn- fixture [f]
  (st/instrument)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


(def ^:private base-url "base-url-113047")


(def ^:private router
  (reitit/router
    [["/Patient" {:name :Patient/type}]
     ["/MeasureReport" {:name :MeasureReport/type}]
     ["/Library" {:name :Library/type}]
     ["/List" {:name :List/type}]
     ["/Condition" {:name :Condition/type}]
     ["/Observation" {:name :Observation/type}]
     ["/MedicationStatement" {:name :MedicationStatement/type}]
     ["/Medication" {:name :Medication/type}]
     ["/Organization" {:name :Organization/type}]
     ["/Encounter" {:name :Encounter/type}]]
    {:syntax :bracket}))


(def ^:private patient-match
  {:data
   {:blaze/context-path ""
    :fhir.resource/type "Patient"}
   :path "/Patient"})


(def ^:private measure-report-match
  {:data
   {:blaze/context-path ""
    :fhir.resource/type "MeasureReport"}
   :path "/MeasureReport"})


(def ^:private list-match
  {:data
   {:blaze/context-path ""
    :fhir.resource/type "List"}
   :path "/List"})


(def ^:private observation-match
  {:data
   {:blaze/context-path ""
    :fhir.resource/type "Observation"}
   :path "/Observation"})


(def ^:private condition-match
  {:data
   {:blaze/context-path ""
    :fhir.resource/type "Condition"}
   :path "/Condition"})


(def ^:private medication-statement-match
  {:data
   {:blaze/context-path ""
    :fhir.resource/type "MedicationStatement"}
   :path "/MedicationStatement"})


(defn- handler [node]
  (-> (ig/init
        {:blaze.interaction/search-type
         {:node node}})
      (:blaze.interaction/search-type)))


(defn- handler-with [txs]
  (fn [request]
    (with-open [node (mem-node-with txs)]
      @((handler node)
        (assoc request
          :blaze/base-url base-url
          ::reitit/router router)))))


(defn- link-url [body link-relation]
  (->> body :link (filter (comp #{link-relation} :relation)) first :url))


(deftest handler-test
  (testing "on unknown search parameter"
    (testing "with strict handling"
      (testing "returns error"
        (testing "normal result"
          (let [{:keys [status body]}
                ((handler-with [])
                 {::reitit/match patient-match
                  :headers {"prefer" "handling=strict"}
                  :params {"foo" "bar"}})]

            (is (= 404 status))

            (given body
              :fhir/type := :fhir/OperationOutcome
              [:issue 0 :severity] := #fhir/code"error"
              [:issue 0 :code] := #fhir/code"not-found"
              [:issue 0 :diagnostics] := "The search-param with code `foo` and type `Patient` was not found.")))

        (testing "summary result"
          (let [{:keys [status body]}
                ((handler-with [])
                 {::reitit/match patient-match
                  :headers {"prefer" "handling=strict"}
                  :params {"foo" "bar" "_summary" "count"}})]

            (is (= 404 status))

            (given body
              :fhir/type := :fhir/OperationOutcome
              [:issue 0 :severity] := #fhir/code"error"
              [:issue 0 :code] := #fhir/code"not-found"
              [:issue 0 :diagnostics] := "The search-param with code `foo` and type `Patient` was not found.")))))

    (testing "with lenient handling"
      (testing "returns results with a self link lacking the unknown search parameter"
        (testing "where the unknown search parameter is the only one"
          (testing "normal result"
            (let [{:keys [status body]}
                  ((handler-with [[[:put {:fhir/type :fhir/Patient :id "0"}]]])
                   {::reitit/match patient-match
                    :headers {"prefer" "handling=lenient"}
                    :params {"foo" "bar"}})]

              (is (= 200 status))

              (testing "the body contains a bundle"
                (is (= :fhir/Bundle (:fhir/type body))))

              (testing "the bundle contains an id"
                (is (string? (:id body))))

              (testing "the bundle type is searchset"
                (is (= #fhir/code"searchset" (:type body))))

              (testing "the total count is 1"
                (is (= #fhir/unsignedInt 1 (:total body))))

              (testing "the bundle contains one entry"
                (is (= 1 (count (:entry body)))))

              (testing "has a self link"
                (is (= #fhir/uri"base-url-113047/Patient?_count=50&__t=1&__page-id=0"
                       (link-url body "self"))))))

          (testing "summary result"
            (let [{:keys [status body]}
                  ((handler-with [[[:put {:fhir/type :fhir/Patient :id "0"}]]])
                   {::reitit/match patient-match
                    :headers {"prefer" "handling=lenient"}
                    :params {"foo" "bar" "_summary" "count"}})]

              (is (= 200 status))

              (testing "the body contains a bundle"
                (is (= :fhir/Bundle (:fhir/type body))))

              (testing "the bundle contains an id"
                (is (string? (:id body))))

              (testing "the bundle type is searchset"
                (is (= #fhir/code"searchset" (:type body))))

              (testing "the total count is 1"
                (is (= #fhir/unsignedInt 1 (:total body))))

              (testing "the bundle contains no entries"
                (is (empty? (:entry body))))

              (testing "has a self link"
                (is (= #fhir/uri"base-url-113047/Patient?_summary=count&_count=50&__t=1"
                       (link-url body "self")))))))

        (testing "with another search parameter"
          (testing "normal result"
            (let [{:keys [status body]}
                  ((handler-with [[[:put {:fhir/type :fhir/Patient :id "0"}]
                                   [:put {:fhir/type :fhir/Patient :id "1"
                                          :active true}]]])
                   {::reitit/match patient-match
                    :headers {"prefer" "handling=lenient"}
                    :params {"foo" "bar" "active" "true"}})]

              (is (= 200 status))

              (testing "the body contains a bundle"
                (is (= :fhir/Bundle (:fhir/type body))))

              (testing "the bundle type is searchset"
                (is (= #fhir/code"searchset" (:type body))))

              (testing "the total count is 1"
                (is (= #fhir/unsignedInt 1 (:total body))))

              (testing "the bundle contains one entry"
                (is (= 1 (count (:entry body)))))

              (testing "has a self link"
                (is (= #fhir/uri"base-url-113047/Patient?active=true&_count=50&__t=1&__page-id=1"
                       (link-url body "self"))))))

          (testing "summary result"
            (let [{:keys [status body]}
                  ((handler-with [[[:put {:fhir/type :fhir/Patient :id "0"}]
                                   [:put {:fhir/type :fhir/Patient :id "1"
                                          :active true}]]])
                   {::reitit/match patient-match
                    :headers {"prefer" "handling=lenient"}
                    :params {"foo" "bar" "active" "true" "_summary" "count"}})]

              (is (= 200 status))

              (testing "the body contains a bundle"
                (is (= :fhir/Bundle (:fhir/type body))))

              (testing "the bundle type is searchset"
                (is (= #fhir/code"searchset" (:type body))))

              (testing "the total count is 1"
                (is (= #fhir/unsignedInt 1 (:total body))))

              (testing "the bundle contains no entries"
                (is (empty? (:entry body))))

              (testing "has a self link"
                (is (= #fhir/uri"base-url-113047/Patient?active=true&_summary=count&_count=50&__t=1"
                       (link-url body "self"))))))))))

  (testing "Returns all existing resources of type"
    (let [{:keys [status body]}
          ((handler-with [[[:put {:fhir/type :fhir/Patient :id "0"}]]])
           {::reitit/match patient-match})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "has a self link"
        (is (= #fhir/uri"base-url-113047/Patient?_count=50&__t=1&__page-id=0"
               (link-url body "self"))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [:meta :lastUpdated] := Instant/EPOCH))

      (testing "the entry has the right search information"
        (given (-> body :entry first :search)
          type/type := :fhir.Bundle.entry/search
          :mode := #fhir/code"match"))))

  (testing "with param _summary equal to count"
    (let [{:keys [status body]}
          ((handler-with [[[:put {:fhir/type :fhir/Patient :id "0"}]]])
           {::reitit/match patient-match
            :params {"_summary" "count"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "has a self link"
        (is (= #fhir/uri"base-url-113047/Patient?_summary=count&_count=50&__t=1"
               (link-url body "self"))))

      (testing "the bundle contains no entries"
        (is (empty? (:entry body))))))

  (testing "with param _count equal to zero"
    (let [{:keys [status body]}
          ((handler-with [[[:put {:fhir/type :fhir/Patient :id "0"}]]])
           {::reitit/match patient-match
            :params {"_count" "0"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "has a self link"
        (is (= #fhir/uri"base-url-113047/Patient?_count=0&__t=1"
               (link-url body "self"))))

      (testing "the bundle contains no entries"
        (is (empty? (:entry body))))))

  (testing "with two patients"
    (with-open [node (mem-node-with
                       [[[:put {:fhir/type :fhir/Patient :id "0"}]
                         [:put {:fhir/type :fhir/Patient :id "1"}]]])]

      (testing "search for all patients with _count=1"
        (let [{:keys [body]}
              @((handler node)
                {:blaze/base-url base-url
                 ::reitit/router router
                 ::reitit/match patient-match
                 :params {"_count" "1"}})]

          (testing "the total count is 2"
            (is (= #fhir/unsignedInt 2 (:total body))))

          (testing "has a self link"
            (is (= #fhir/uri"base-url-113047/Patient?_count=1&__t=1&__page-id=0"
                   (link-url body "self"))))

          (testing "has a next link"
            (is (= #fhir/uri"base-url-113047/Patient?_count=1&__t=1&__page-id=1"
                   (link-url body "next"))))

          (testing "the bundle contains one entry"
            (is (= 1 (count (:entry body)))))))

      (testing "following the self link"
        (let [{:keys [body]}
              @((handler node)
                {:blaze/base-url base-url
                 ::reitit/router router
                 ::reitit/match patient-match
                 :params {"_count" "1" "__t" "1" "__page-id" "0"}})]

          (testing "the total count is 2"
            (is (= #fhir/unsignedInt 2 (:total body))))

          (testing "has a self link"
            (is (= #fhir/uri"base-url-113047/Patient?_count=1&__t=1&__page-id=0"
                   (link-url body "self"))))

          (testing "has a next link"
            (is (= #fhir/uri"base-url-113047/Patient?_count=1&__t=1&__page-id=1"
                   (link-url body "next"))))

          (testing "the bundle contains one entry"
            (is (= 1 (count (:entry body)))))))

      (testing "following the next link"
        (let [{:keys [body]}
              @((handler node)
                {:blaze/base-url base-url
                 ::reitit/router router
                 ::reitit/match patient-match
                 :params {"_count" "1" "__t" "1" "__page-id" "1"}})]

          (testing "the total count is 2"
            (is (= #fhir/unsignedInt 2 (:total body))))

          (testing "has a self link"
            (is (= #fhir/uri"base-url-113047/Patient?_count=1&__t=1&__page-id=1"
                   (link-url body "self"))))

          (testing "has no next link"
            (is (nil? (link-url body "next"))))

          (testing "the bundle contains one entry"
            (is (= 1 (count (:entry body)))))))))

  (testing "with three patients"
    (with-open [node (mem-node-with
                       [[[:put {:fhir/type :fhir/Patient :id "0"}]
                         [:put {:fhir/type :fhir/Patient :id "1" :active true}]
                         [:put {:fhir/type :fhir/Patient :id "2" :active true}]]])]

      (testing "search for active patients with _summary=count"
        (testing "with strict handling"
          (let [{:keys [body]}
                @((handler node)
                  {:blaze/base-url base-url
                   ::reitit/router router
                   ::reitit/match patient-match
                   :headers {"prefer" "handling=strict"}
                   :params {"active" "true" "_summary" "count"}})]

            (testing "their is a total count because we used _summary=count"
              (is (= #fhir/unsignedInt 2 (:total body))))))

        (testing "with default handling"
          (let [{:keys [body]}
                @((handler node)
                  {:blaze/base-url base-url
                   ::reitit/router router
                   ::reitit/match patient-match
                   :params {"active" "true" "_summary" "count"}})]

            (testing "their is a total count because we used _summary=count"
              (is (= #fhir/unsignedInt 2 (:total body)))))))

      (testing "search for active patients with _count=1"
        (let [{:keys [body]}
              @((handler node)
                {:blaze/base-url base-url
                 ::reitit/router router
                 ::reitit/match patient-match
                 :params {"active" "true" "_count" "1"}})]

          (testing "their is no total count because we have clauses and we have
                    more hits than page-size"
            (is (nil? (:total body))))

          (testing "has a self link"
            (is (= #fhir/uri"base-url-113047/Patient?active=true&_count=1&__t=1&__page-id=1"
                   (link-url body "self"))))

          (testing "has a next link"
            (is (= #fhir/uri"base-url-113047/Patient?active=true&_count=1&__t=1&__page-id=2"
                   (link-url body "next"))))

          (testing "the bundle contains one entry"
            (is (= 1 (count (:entry body)))))))

      (testing "following the self link"
        (let [{:keys [body]}
              @((handler node)
                {:blaze/base-url base-url
                 ::reitit/router router
                 ::reitit/match patient-match
                 :params {"active" "true" "_count" "1" "__t" "1" "__page-id" "1"}})]

          (testing "their is no total count because we have clauses and we have
                    more hits than page-size"
            (is (nil? (:total body))))

          (testing "has a self link"
            (is (= #fhir/uri"base-url-113047/Patient?active=true&_count=1&__t=1&__page-id=1"
                   (link-url body "self"))))

          (testing "has a next link"
            (is (= #fhir/uri"base-url-113047/Patient?active=true&_count=1&__t=1&__page-id=2"
                   (link-url body "next"))))

          (testing "the bundle contains one entry"
            (is (= 1 (count (:entry body)))))))

      (testing "following the next link"
        (let [{:keys [body]}
              @((handler node)
                {:blaze/base-url base-url
                 ::reitit/router router
                 ::reitit/match patient-match
                 :params {"active" "true" "_count" "1" "__t" "1" "__page-id" "2"}})]

          (testing "their is no total count because we have clauses and we have
                    more hits than page-size"
            (is (nil? (:total body))))

          (testing "has a self link"
            (is (= #fhir/uri"base-url-113047/Patient?active=true&_count=1&__t=1&__page-id=2"
                   (link-url body "self"))))

          (testing "has no next link"
            (is (nil? (link-url body "next"))))

          (testing "the bundle contains one entry"
            (is (= 1 (count (:entry body)))))))))


  (testing "Id search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Patient :id "0"}]
               [:put {:fhir/type :fhir/Patient :id "1"}]]])
           {::reitit/match {:data {:fhir.resource/type "Patient"}}
            :params {"_id" "0"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          :fhir/type := :fhir/Patient
          :id := "0"))))

  (testing "Multiple Id search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Patient :id "0"}]
               [:put {:fhir/type :fhir/Patient :id "1"}]
               [:put {:fhir/type :fhir/Patient :id "2"}]]])
           {::reitit/match {:data {:fhir.resource/type "Patient"}}
            :params {"_id" "0,2"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 2"
        (is (= #fhir/unsignedInt 2 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 2 (count (:entry body)))))

      (testing "the first entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/0" (-> body :entry first :fullUrl))))

      (testing "the second entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/2" (-> body :entry second :fullUrl))))

      (testing "the first entry has the right resource"
        (given (-> body :entry first :resource)
          :fhir/type := :fhir/Patient
          :id := "0"))

      (testing "the second entry has the right resource"
        (given (-> body :entry second :resource)
          :fhir/type := :fhir/Patient
          :id := "2"))))

  (testing "_list search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Patient :id "0"}]
               [:put {:fhir/type :fhir/Patient :id "1"}]
               [:put {:fhir/type :fhir/List :id "0"
                      :entry
                      [{:fhir/type :fhir.List/entry
                        :item
                        #fhir/Reference
                            {:reference "Patient/0"}}]}]]])
           {::reitit/match {:data {:fhir.resource/type "Patient"}}
            :params {"_list" "0"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          :fhir/type := :fhir/Patient
          :id := "0"))))

  (testing "_has search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Patient :id "0"}]
               [:put {:fhir/type :fhir/Patient :id "1"}]
               [:put {:fhir/type :fhir/Observation :id "0"
                      :subject
                      #fhir/Reference
                          {:reference "Patient/0"}
                      :code
                      #fhir/CodeableConcept
                          {:coding
                           [#fhir/Coding
                               {:system #fhir/uri"http://loinc.org"
                                :code #fhir/code"8480-6"}]}
                      :value
                      #fhir/Quantity
                          {:value 130M
                           :code #fhir/code"mm[Hg]"
                           :system #fhir/uri"http://unitsofmeasure.org"}}]
               [:put {:fhir/type :fhir/Observation :id "1"
                      :subject
                      #fhir/Reference
                          {:reference "Patient/0"}
                      :code
                      #fhir/CodeableConcept
                          {:coding
                           [#fhir/Coding
                               {:system #fhir/uri"http://loinc.org"
                                :code #fhir/code"8480-6"}]}
                      :value
                      #fhir/Quantity
                          {:value 150M
                           :code #fhir/code"mm[Hg]"
                           :system #fhir/uri"http://unitsofmeasure.org"}}]
               [:put {:fhir/type :fhir/Observation :id "2"
                      :subject
                      #fhir/Reference
                          {:reference "Patient/1"}
                      :code
                      #fhir/CodeableConcept
                          {:coding
                           [#fhir/Coding
                               {:system #fhir/uri"http://loinc.org"
                                :code #fhir/code"8480-6"}]}
                      :value
                      #fhir/Quantity
                          {:value 100M
                           :code #fhir/code"mm[Hg]"
                           :system #fhir/uri"http://unitsofmeasure.org"}}]]])
           {::reitit/match {:data {:fhir.resource/type "Patient"}}
            :params {"_has:Observation:patient:code-value-quantity" "8480-6$ge130"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          :fhir/type := :fhir/Patient
          :id := "0"))))

  (testing "Patient identifier search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Patient :id "0"
                      :identifier [#fhir/Identifier{:value "0"}]}]
               [:put {:fhir/type :fhir/Patient :id "1"
                      :identifier [#fhir/Identifier{:value "1"}]}]]])
           {::reitit/match patient-match
            :params {"identifier" "0"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          [:identifier 0 :value] := "0"))))

  (testing "Patient language search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Patient :id "0"
                      :communication
                      [{:fhir/type :fhir.Patient/communication
                        :language
                        #fhir/CodeableConcept
                            {:coding
                             [#fhir/Coding
                                 {:system #fhir/uri"urn:ietf:bcp:47"
                                  :code #fhir/code"de"}]}}
                       {:fhir/type :fhir.Patient/communication
                        :language
                        #fhir/CodeableConcept
                            {:coding
                             [#fhir/Coding
                                 {:system #fhir/uri"urn:ietf:bcp:47"
                                  :code #fhir/code"en"}]}}]}]
               [:put {:fhir/type :fhir/Patient :id "1"
                      :communication
                      [{:fhir/type :fhir.Patient/communication
                        :language
                        #fhir/CodeableConcept
                            {:coding
                             [#fhir/Coding
                                 {:system #fhir/uri"urn:ietf:bcp:47"
                                  :code #fhir/code"de"}]}}]}]]])
           {::reitit/match patient-match
            :params {"language" ["de" "en"]}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Patient/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (is (= "0" (-> body :entry first :resource :id))))))

  (testing "Library title search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Library :id "0" :title "ab"}]
               [:put {:fhir/type :fhir/Library :id "1" :title "b"}]]])
           {::reitit/match {:data {:fhir.resource/type "Library"}}
            :params {"title" "A"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Library/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          :fhir/type := :fhir/Library
          :id := "0"))))

  #_(testing "Library title:contains search"
      (let [{:keys [status body]}
            ((handler-with
               [[[:put {:fhir/type :fhir/Library :id "0" :title "bab"}]
                 [:put {:fhir/type :fhir/Library :id "1" :title "b"}]]])
             {::reitit/match {:data {:fhir.resource/type "Library"}}
              :params {"title:contains" "A"}})]

        (is (= 200 status))

        (testing "the body contains a bundle"
          (is (= :fhir/Bundle (:fhir/type body))))

        (testing "the bundle type is searchset"
          (is (= #fhir/code"searchset" (:type body))))

        (testing "the bundle contains one entry"
          (is (= 1 (count (:entry body)))))

        (testing "the entry has the right fullUrl"
          (is (= "/Library/0" (-> body :entry first :fullUrl))))

        (testing "the entry has the right resource"
          (given (-> body :entry first :resource)
            :fhir/type := :fhir/Library
            :id := "0"))))

  (testing "MeasureReport measure search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/MeasureReport :id "0"
                      :measure #fhir/canonical"http://server.com/Measure/0"}]]
              [[:put {:fhir/type :fhir/MeasureReport :id "1"
                      :measure #fhir/canonical"http://server.com/Measure/1"}]]])
           {::reitit/match measure-report-match
            :params {"measure" "http://server.com/Measure/0"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/MeasureReport/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          :measure := #fhir/canonical"http://server.com/Measure/0"))))

  #_(testing "Measure title sort asc"
      (let [measure-1 {:fhir/type :fhir/Measure "id" "1"}
            measure-2 {:fhir/type :fhir/Measure "id" "2"}]
        (datomic-test-util/stub-find-search-param-by-type-and-code
          ::db "Measure" "title" #{::search-param})
        (datomic-test-util/stub-list-resources-sorted-by
          ::db "Measure" ::search-param #{[::measure-1 ::measure-2]})
        (datomic-test-util/stub-pull-resource*-fn
          ::db "Measure" #{::measure-1 ::measure-2}
          (fn [_ _ r] (case r ::measure-1 measure-1 ::measure-2 measure-2)))
        (datomic-test-util/stub-type-total ::db "Measure" 2)
        (test-util/stub-instance-url-fn
          ::router "Measure" #{"1" "2"}
          (fn [_ _ id] (keyword (str "measure-url-" id))))

        (let [{:keys [status body]}
              @((handler ::node)
                {::reitit/router ::router
                 ::reitit/match {:data {:fhir.resource/type "Measure"}}
                 :params {"_sort" "title"}})]

          (is (= 200 status))

          (testing "the body contains a bundle"
            (is (= :fhir/Bundle (:fhir/type body))))

          (testing "the bundle type is searchset"
            (is (= #fhir/code"searchset" (:type body))))

          (testing "the total count is 2"
            (is (= #fhir/unsignedInt 2 (:total body))))

          (testing "the bundle contains two entries"
            (is (= 2 (count (:entry body)))))

          (testing "the first entry has the right fullUrl"
            (is (= :measure-url-1 (-> body :entry first :fullUrl))))

          (testing "the second entry has the right fullUrl"
            (is (= :measure-url-2 (-> body :entry second :fullUrl))))

          (testing "the first entry has the right resource"
            (is (= measure-1 (-> body :entry first :resource))))

          (testing "the second entry has the right resource"
            (is (= measure-2 (-> body :entry second :resource)))))))

  #_(testing "Measure title sort desc"
      (let [measure-1 {:fhir/type :fhir/Measure "id" "1"}
            measure-2 {:fhir/type :fhir/Measure "id" "2"}]
        (datomic-test-util/stub-find-search-param-by-type-and-code
          ::db "Measure" "title" #{::search-param})
        (datomic-test-util/stub-list-resources-sorted-by
          ::db "Measure" ::search-param #{[::measure-1 ::measure-2]})
        (datomic-test-util/stub-pull-resource*-fn
          ::db "Measure" #{::measure-1 ::measure-2}
          (fn [_ _ r] (case r ::measure-1 measure-1 ::measure-2 measure-2)))
        (datomic-test-util/stub-type-total ::db "Measure" 2)
        (test-util/stub-instance-url-fn
          ::router "Measure" #{"1" "2"}
          (fn [_ _ id] (keyword (str "measure-url-" id))))

        (let [{:keys [status body]}
              @((handler ::node)
                {::reitit/router ::router
                 ::reitit/match {:data {:fhir.resource/type "Measure"}}
                 :params {"_sort" "-title"}})]

          (is (= 200 status))

          (testing "the body contains a bundle"
            (is (= :fhir/Bundle (:fhir/type body))))

          (testing "the bundle type is searchset"
            (is (= #fhir/code"searchset" (:type body))))

          (testing "the total count is 2"
            (is (= #fhir/unsignedInt 2 (:total body))))

          (testing "the bundle contains two entries"
            (is (= 2 (count (:entry body)))))

          (testing "the first entry has the right fullUrl"
            (is (= :measure-url-2 (-> body :entry first :fullUrl))))

          (testing "the second entry has the right fullUrl"
            (is (= :measure-url-1 (-> body :entry second :fullUrl))))

          (testing "the first entry has the right resource"
            (is (= measure-2 (-> body :entry first :resource))))

          (testing "the second entry has the right resource"
            (is (= measure-1 (-> body :entry second :resource)))))))

  (testing "List item search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/List :id "id-123058"
                      :entry
                      [{:fhir/type :fhir.List/entry
                        :item
                        #fhir/Reference
                            {:identifier
                             #fhir/Identifier
                                 {:system #fhir/uri"system-122917"
                                  :value "value-122931"}}}]}]
               [:put {:fhir/type :fhir/List :id "id-143814"
                      :entry
                      [{:fhir/type :fhir.List/entry
                        :item
                        #fhir/Reference
                            {:identifier
                             #fhir/Identifier
                                 {:system #fhir/uri"system-122917"
                                  :value "value-143818"}}}]}]]])
           {::reitit/match list-match
            :params {"item:identifier" "system-122917|value-143818"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/List/id-143814" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          :id := "id-143814"))))

  (testing "Observation combo-code-value-quantity search"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Observation :id "id-121049"
                      :component
                      [{:fhir/type :fhir.Observation/component
                        :code
                        #fhir/CodeableConcept
                            {:coding
                             [#fhir/Coding
                                 {:system #fhir/uri"http://loinc.org"
                                  :code #fhir/code"8480-6"}]}
                        :value
                        #fhir/Quantity
                            {:value 140M
                             :system #fhir/uri"http://unitsofmeasure.org"
                             :code #fhir/code"mm[Hg]"}}
                       {:fhir/type :fhir.Observation/component
                        :code
                        #fhir/CodeableConcept
                            {:coding
                             [#fhir/Coding
                                 {:system #fhir/uri"http://loinc.org"
                                  :code #fhir/code"8462-4"}]}
                        :value
                        #fhir/Quantity
                            {:value 90M
                             :system #fhir/uri"http://unitsofmeasure.org"
                             :code #fhir/code"mm[Hg]"}}]}]]
              [[:put {:fhir/type :fhir/Observation :id "id-123130"
                      :component
                      [{:fhir/type :fhir.Observation/component
                        :code
                        #fhir/CodeableConcept
                            {:coding
                             [#fhir/Coding
                                 {:system #fhir/uri"http://loinc.org"
                                  :code #fhir/code"8480-6"}]}
                        :value
                        #fhir/Quantity
                            {:value 150M
                             :system #fhir/uri"http://unitsofmeasure.org"
                             :code #fhir/code"mm[Hg]"}}
                       {:fhir/type :fhir.Observation/component
                        :code
                        #fhir/CodeableConcept
                            {:coding
                             [#fhir/Coding
                                 {:system #fhir/uri"http://loinc.org"
                                  :code #fhir/code"8462-4"}]}
                        :value
                        #fhir/Quantity
                            {:value 100M
                             :system #fhir/uri"http://unitsofmeasure.org"
                             :code #fhir/code"mm[Hg]"}}]}]]])
           {::reitit/match observation-match
            :params
            {"combo-code-value-quantity"
             ["http://loinc.org|8480-6$ge140|mm[Hg]"
              "http://loinc.org|8462-4$ge90|mm[Hg]"]
             "_count" "1"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "has a next link"
        (is (= #fhir/uri"base-url-113047/Observation?combo-code-value-quantity=http%3A%2F%2Floinc.org%7C8480-6%24ge140%7Cmm%5BHg%5D&combo-code-value-quantity=http%3A%2F%2Floinc.org%7C8462-4%24ge90%7Cmm%5BHg%5D&_count=1&__t=2&__page-id=id-123130"
               (link-url body "next"))))))

  (testing "Duplicate OR Search Parameters have no Effect (#293)"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Condition :id "0"
                      :code
                      #fhir/CodeableConcept
                          {:coding
                           [#fhir/Coding
                               {:system #fhir/uri"http://fhir.de/CodeSystem/dimdi/icd-10-gm"
                                :code #fhir/code"C71.4"}]}}]]])
           {::reitit/match condition-match
            :params {"code" "C71.4,C71.4"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the total count is 1"
        (is (= #fhir/unsignedInt 1 (:total body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Condition/0" (-> body :entry first :fullUrl))))

      (testing "the entry has the right resource"
        (given (-> body :entry first :resource)
          [:code :coding 0 :code] := #fhir/code"C71.4"))))

  (testing "Paging works with OR Search Parameters"
    (let [{:keys [status body]}
          ((handler-with
             [[[:put {:fhir/type :fhir/Condition :id "0"
                      :code
                      #fhir/CodeableConcept
                          {:coding
                           [#fhir/Coding
                               {:code #fhir/code"0"}]}}]
               [:put {:fhir/type :fhir/Condition :id "2"
                      :code
                      #fhir/CodeableConcept
                          {:coding
                           [#fhir/Coding
                               {:code #fhir/code"0"}]}}]
               [:put {:fhir/type :fhir/Condition :id "1"
                      :code
                      #fhir/CodeableConcept
                          {:coding
                           [#fhir/Coding
                               {:code #fhir/code"1"}]}}]]])
           {::reitit/match condition-match
            :params {"code" "0,1" "_count" "2"
                     "__t" "1" "__page-id" "1"}})]

      (is (= 200 status))

      (testing "the body contains a bundle"
        (is (= :fhir/Bundle (:fhir/type body))))

      (testing "the bundle type is searchset"
        (is (= #fhir/code"searchset" (:type body))))

      (testing "the bundle contains one entry"
        (is (= 1 (count (:entry body)))))

      (testing "the entry has the right fullUrl"
        (is (= #fhir/uri"base-url-113047/Condition/1" (-> body :entry first :fullUrl))))))

  (testing "Include Resources"
    (testing "direct include"
      (let [{:keys [status body]}
            ((handler-with
               [[[:put {:fhir/type :fhir/Patient :id "0"}]
                 [:put {:fhir/type :fhir/Observation :id "0"
                        :subject #fhir/Reference{:reference "Patient/0"}}]]])
             {::reitit/match observation-match
              :params {"_include" "Observation:subject"}})]

        (is (= 200 status))

        (testing "the body contains a bundle"
          (is (= :fhir/Bundle (:fhir/type body))))

        (testing "the bundle type is searchset"
          (is (= #fhir/code"searchset" (:type body))))

        (testing "the total count is 1"
          (is (= #fhir/unsignedInt 1 (:total body))))

        (testing "has a self link"
          (is (= #fhir/uri"base-url-113047/Observation?_include=Observation%3Asubject&_count=50&__t=1&__page-id=0"
                 (link-url body "self"))))

        (testing "the bundle contains two entries"
          (is (= 2 (count (:entry body)))))

        (testing "the first entry is the matched Observation"
          (given (-> body :entry first)
            :fullUrl := #fhir/uri"base-url-113047/Observation/0"
            [:resource :fhir/type] := :fhir/Observation
            [:search :mode] := #fhir/code"match"))

        (testing "the second entry is the included Patient"
          (given (-> body :entry second)
            :fullUrl := #fhir/uri"base-url-113047/Patient/0"
            [:resource :fhir/type] := :fhir/Patient
            [:search :mode] := #fhir/code"include")))

      (testing "with non-matching target type"
        (let [{:keys [status body]}
              ((handler-with
                 [[[:put {:fhir/type :fhir/Patient :id "0"}]
                   [:put {:fhir/type :fhir/Observation :id "0"
                          :subject #fhir/Reference{:reference "Patient/0"}}]]])
               {::reitit/match observation-match
                :params {"_include" "Observation:subject:Group"}})]

          (is (= 200 status))

          (testing "the body contains a bundle"
            (is (= :fhir/Bundle (:fhir/type body))))

          (testing "the bundle type is searchset"
            (is (= #fhir/code"searchset" (:type body))))

          (testing "the total count is 1"
            (is (= #fhir/unsignedInt 1 (:total body))))

          (testing "the bundle contains one entry"
            (is (= 1 (count (:entry body)))))

          (testing "the first entry is the matched Observation"
            (given (-> body :entry first)
              :fullUrl := #fhir/uri"base-url-113047/Observation/0"
              [:resource :fhir/type] := :fhir/Observation
              [:search :mode] := #fhir/code"match"))))

      (testing "includes don't appear twice"
        (let [{:keys [status body]}
              ((handler-with
                 [[[:put {:fhir/type :fhir/Patient :id "0"}]
                   [:put {:fhir/type :fhir/Observation :id "1"
                          :subject #fhir/Reference{:reference "Patient/0"}}]
                   [:put {:fhir/type :fhir/Observation :id "2"
                          :subject #fhir/Reference{:reference "Patient/0"}}]]])
               {::reitit/match observation-match
                :params {"_include" "Observation:subject"}})]

          (is (= 200 status))

          (testing "the body contains a bundle"
            (is (= :fhir/Bundle (:fhir/type body))))

          (testing "the bundle type is searchset"
            (is (= #fhir/code"searchset" (:type body))))

          (testing "the total count is 2"
            (is (= #fhir/unsignedInt 2 (:total body))))

          (testing "the bundle contains three entries"
            (is (= 3 (count (:entry body)))))

          (testing "the first entry is the first matched Observation"
            (given (-> body :entry first)
              :fullUrl := #fhir/uri"base-url-113047/Observation/1"
              [:resource :fhir/type] := :fhir/Observation
              [:search :mode] := #fhir/code"match"))

          (testing "the second entry is the second matched Observation"
            (given (-> body :entry second)
              :fullUrl := #fhir/uri"base-url-113047/Observation/2"
              [:resource :fhir/type] := :fhir/Observation
              [:search :mode] := #fhir/code"match"))

          (testing "the third entry is the included Patient"
            (given (-> body :entry (nth 2))
              :fullUrl := #fhir/uri"base-url-113047/Patient/0"
              [:resource :fhir/type] := :fhir/Patient
              [:search :mode] := #fhir/code"include"))))

      (testing "two includes"
        (let [{:keys [status body]}
              ((handler-with
                 [[[:put {:fhir/type :fhir/Patient :id "0"}]
                   [:put {:fhir/type :fhir/Encounter :id "1"
                          :subject #fhir/Reference{:reference "Patient/0"}}]
                   [:put {:fhir/type :fhir/Observation :id "2"
                          :subject #fhir/Reference{:reference "Patient/0"}
                          :encounter #fhir/Reference{:reference "Encounter/1"}}]]])
               {::reitit/match observation-match
                :params
                {"_include" ["Observation:subject" "Observation:encounter"]}})]

          (is (= 200 status))

          (testing "the body contains a bundle"
            (is (= :fhir/Bundle (:fhir/type body))))

          (testing "the bundle type is searchset"
            (is (= #fhir/code"searchset" (:type body))))

          (testing "the total count is 1"
            (is (= #fhir/unsignedInt 1 (:total body))))

          (testing "the bundle contains three entries"
            (is (= 3 (count (:entry body)))))

          (testing "the first entry is the matched Observation"
            (given (-> body :entry first)
              :fullUrl := #fhir/uri"base-url-113047/Observation/2"
              [:resource :fhir/type] := :fhir/Observation
              [:search :mode] := #fhir/code"match"))

          (testing "the second entry is the included Encounter"
            (given (-> body :entry (nth 2))
              :fullUrl := #fhir/uri"base-url-113047/Encounter/1"
              [:resource :fhir/type] := :fhir/Encounter
              [:search :mode] := #fhir/code"include"))

          (testing "the third entry is the included Patient"
            (given (-> body :entry second)
              :fullUrl := #fhir/uri"base-url-113047/Patient/0"
              [:resource :fhir/type] := :fhir/Patient
              [:search :mode] := #fhir/code"include"))))

      (testing "with paging"
        (with-open [node (mem-node-with
                           [[[:put {:fhir/type :fhir/Patient :id "0"}]
                             [:put {:fhir/type :fhir/Observation :id "1"
                                    :subject
                                    #fhir/Reference
                                        {:reference "Patient/0"}}]
                             [:put {:fhir/type :fhir/Patient :id "2"}]
                             [:put {:fhir/type :fhir/Observation :id "3"
                                    :subject
                                    #fhir/Reference
                                        {:reference "Patient/2"}}]]])]
          (let [{:keys [status body]}
                @((handler node)
                  {:blaze/base-url base-url
                   ::reitit/router router
                   ::reitit/match observation-match
                   :params {"_include" "Observation:subject" "_count" "1"}})]

            (is (= 200 status))

            (testing "the body contains a bundle"
              (is (= :fhir/Bundle (:fhir/type body))))

            (testing "the bundle type is searchset"
              (is (= #fhir/code"searchset" (:type body))))

            (testing "the total count is 2"
              (is (= #fhir/unsignedInt 2 (:total body))))

            (testing "has a next link"
              (is (= #fhir/uri"base-url-113047/Observation?_include=Observation%3Asubject&_count=1&__t=1&__page-id=3"
                     (link-url body "next"))))

            (testing "the bundle contains two entries"
              (is (= 2 (count (:entry body)))))

            (testing "the first entry is the matched Observation"
              (given (-> body :entry first)
                :fullUrl := #fhir/uri"base-url-113047/Observation/1"
                [:resource :fhir/type] := :fhir/Observation
                [:search :mode] := #fhir/code"match"))

            (testing "the second entry is the included Patient"
              (given (-> body :entry second)
                :fullUrl := #fhir/uri"base-url-113047/Patient/0"
                [:resource :fhir/type] := :fhir/Patient
                [:search :mode] := #fhir/code"include"))

            (testing "second page"
              (let [{:keys [status body]}
                    @((handler node)
                      {:blaze/base-url base-url
                       ::reitit/router router
                       ::reitit/match observation-match
                       :params {"_include" "Observation:subject" "_count" "2"
                                "__t" "1" "__page-id" "3"}})]

                (is (= 200 status))

                (testing "the body contains a bundle"
                  (is (= :fhir/Bundle (:fhir/type body))))

                (testing "the bundle type is searchset"
                  (is (= #fhir/code"searchset" (:type body))))

                (testing "the total count is 2"
                  (is (= #fhir/unsignedInt 2 (:total body))))

                (testing "has a self link"
                  (is (= #fhir/uri"base-url-113047/Observation?_include=Observation%3Asubject&_count=2&__t=1&__page-id=3"
                         (link-url body "self"))))

                (testing "the bundle contains two entries"
                  (is (= 2 (count (:entry body)))))

                (testing "the first entry is the matched Observation"
                  (given (-> body :entry first)
                    :fullUrl := #fhir/uri"base-url-113047/Observation/3"
                    [:resource :fhir/type] := :fhir/Observation
                    [:search :mode] := #fhir/code"match"))

                (testing "the second entry is the included Patient"
                  (given (-> body :entry second)
                    :fullUrl := #fhir/uri"base-url-113047/Patient/2"
                    [:resource :fhir/type] := :fhir/Patient
                    [:search :mode] := #fhir/code"include"))))))))

    (testing "iterative include"
      (let [{:keys [status body]}
            ((handler-with
               [[[:put {:fhir/type :fhir/MedicationStatement :id "0"
                        :medication
                        #fhir/Reference
                            {:reference "Medication/0"}}]
                 [:put {:fhir/type :fhir/Medication :id "0"
                        :manufacturer
                        #fhir/Reference
                            {:reference "Organization/0"}}]
                 [:put {:fhir/type :fhir/Organization :id "0"}]]])
             {::reitit/match medication-statement-match
              :params
              {"_include" "MedicationStatement:medication"
               "_include:iterate" "Medication:manufacturer"}})]

        (is (= 200 status))

        (testing "the body contains a bundle"
          (is (= :fhir/Bundle (:fhir/type body))))

        (testing "the bundle type is searchset"
          (is (= #fhir/code"searchset" (:type body))))

        (testing "the total count is 1"
          (is (= #fhir/unsignedInt 1 (:total body))))

        (testing "the bundle contains two entries"
          (is (= 3 (count (:entry body)))))

        (testing "the first entry is the matched MedicationStatement"
          (given (-> body :entry first)
            :fullUrl := #fhir/uri"base-url-113047/MedicationStatement/0"
            [:resource :fhir/type] := :fhir/MedicationStatement
            [:search :mode] := #fhir/code"match"))

        (testing "the second entry is the included Organization"
          (given (-> body :entry second)
            :fullUrl := #fhir/uri"base-url-113047/Organization/0"
            [:resource :fhir/type] := :fhir/Organization
            [:search :mode] := #fhir/code"include"))

        (testing "the third entry is the included Medication"
          (given (-> body :entry (nth 2))
            :fullUrl := #fhir/uri"base-url-113047/Medication/0"
            [:resource :fhir/type] := :fhir/Medication
            [:search :mode] := #fhir/code"include"))))

    (testing "non-iterative include doesn't work iterative"
      (let [{:keys [status body]}
            ((handler-with
               [[[:put {:fhir/type :fhir/MedicationStatement :id "0"
                        :medication
                        #fhir/Reference
                            {:reference "Medication/0"}}]
                 [:put {:fhir/type :fhir/Medication :id "0"
                        :manufacturer
                        #fhir/Reference
                            {:reference "Organization/0"}}]
                 [:put {:fhir/type :fhir/Organization :id "0"}]]])
             {::reitit/match medication-statement-match
              :params
              {"_include"
               ["MedicationStatement:medication" "Medication:manufacturer"]}})]

        (is (= 200 status))

        (testing "the body contains a bundle"
          (is (= :fhir/Bundle (:fhir/type body))))

        (testing "the bundle type is searchset"
          (is (= #fhir/code"searchset" (:type body))))

        (testing "the total count is 1"
          (is (= #fhir/unsignedInt 1 (:total body))))

        (testing "the bundle contains two entries"
          (is (= 2 (count (:entry body)))))

        (testing "the first entry is the matched MedicationStatement"
          (given (-> body :entry first)
            :fullUrl := #fhir/uri"base-url-113047/MedicationStatement/0"
            [:resource :fhir/type] := :fhir/MedicationStatement
            [:search :mode] := #fhir/code"match"))

        (testing "the second entry is the included Medication"
          (given (-> body :entry second)
            :fullUrl := #fhir/uri"base-url-113047/Medication/0"
            [:resource :fhir/type] := :fhir/Medication
            [:search :mode] := #fhir/code"include"))))

    (testing "revinclude"
      (let [{:keys [status body]}
            ((handler-with
               [[[:put {:fhir/type :fhir/Patient :id "0"}]
                 [:put {:fhir/type :fhir/Observation :id "1"
                        :subject
                        #fhir/Reference
                            {:reference "Patient/0"}}]]])
             {::reitit/match patient-match
              :params {"_revinclude" "Observation:subject"}})]

        (is (= 200 status))

        (testing "the body contains a bundle"
          (is (= :fhir/Bundle (:fhir/type body))))

        (testing "the bundle type is searchset"
          (is (= #fhir/code"searchset" (:type body))))

        (testing "the total count is 1"
          (is (= #fhir/unsignedInt 1 (:total body))))

        (testing "has a self link"
          (is (= #fhir/uri"base-url-113047/Patient?_revinclude=Observation%3Asubject&_count=50&__t=1&__page-id=0"
                 (link-url body "self"))))

        (testing "the bundle contains two entries"
          (is (= 2 (count (:entry body)))))

        (testing "the first entry is the matched Patient"
          (given (-> body :entry first)
            :fullUrl := #fhir/uri"base-url-113047/Patient/0"
            [:resource :fhir/type] := :fhir/Patient
            [:search :mode] := #fhir/code"match"))

        (testing "the second entry is the included Observation"
          (given (-> body :entry second)
            :fullUrl := #fhir/uri"base-url-113047/Observation/1"
            [:resource :fhir/type] := :fhir/Observation
            [:search :mode] := #fhir/code"include")))

      (testing "two revincludes"
        (let [{:keys [status body]}
              ((handler-with
                 [[[:put {:fhir/type :fhir/Patient :id "0"}]
                   [:put {:fhir/type :fhir/Observation :id "1"
                          :subject
                          #fhir/Reference
                              {:reference "Patient/0"}}]
                   [:put {:fhir/type :fhir/Condition :id "2"
                          :subject
                          #fhir/Reference
                              {:reference "Patient/0"}}]]])
               {::reitit/match patient-match
                :params
                {"_revinclude" ["Observation:subject" "Condition:subject"]}})]

          (is (= 200 status))

          (testing "the body contains a bundle"
            (is (= :fhir/Bundle (:fhir/type body))))

          (testing "the bundle type is searchset"
            (is (= #fhir/code"searchset" (:type body))))

          (testing "the total count is 1"
            (is (= #fhir/unsignedInt 1 (:total body))))

          (testing "has a self link"
            (is (= #fhir/uri"base-url-113047/Patient?_revinclude=Observation%3Asubject&_revinclude=Condition%3Asubject&_count=50&__t=1&__page-id=0"
                   (link-url body "self"))))

          (testing "the bundle contains two entries"
            (is (= 3 (count (:entry body)))))

          (testing "the first entry is the matched Patient"
            (given (-> body :entry first)
              :fullUrl := #fhir/uri"base-url-113047/Patient/0"
              [:resource :fhir/type] := :fhir/Patient
              [:search :mode] := #fhir/code"match"))

          (testing "the second entry is the included Observation"
            (given (-> body :entry second)
              :fullUrl := #fhir/uri"base-url-113047/Observation/1"
              [:resource :fhir/type] := :fhir/Observation
              [:search :mode] := #fhir/code"include"))

          (testing "the third entry is the included Condition"
            (given (-> body :entry (nth 2))
              :fullUrl := #fhir/uri"base-url-113047/Condition/2"
              [:resource :fhir/type] := :fhir/Condition
              [:search :mode] := #fhir/code"include")))))

    (testing "invalid include parameter"
      (let [{:keys [status body]}
            ((handler-with [])
             {::reitit/match patient-match
              :headers {"prefer" "handling=strict"}
              :params {"_include" "Observation"}})]

        (is (= 400 status))

        (given body
          :fhir/type := :fhir/OperationOutcome
          [:issue 0 :severity] := #fhir/code"error"
          [:issue 0 :code] := #fhir/code"invalid"
          [:issue 0 :diagnostics] := "Missing search parameter code in _include search parameter with source type `Observation`.")))))