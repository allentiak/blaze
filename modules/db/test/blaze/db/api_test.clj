(ns blaze.db.api-test
  "Main high-level test of all database API functions."
  (:require
    [blaze.async.comp :as ac]
    [blaze.async.comp-spec]
    [blaze.coll.core :as coll]
    [blaze.db.api :as d]
    [blaze.db.api-spec]
    [blaze.db.impl.db-spec]
    [blaze.db.kv.mem :refer [new-mem-kv-store]]
    [blaze.db.node :as node]
    [blaze.db.node-spec]
    [blaze.db.resource-store :as rs]
    [blaze.db.resource-store.kv :refer [new-kv-resource-store]]
    [blaze.db.search-param-registry :as sr]
    [blaze.db.tx-log-spec]
    [blaze.db.tx-log.local :refer [new-local-tx-log]]
    [blaze.db.tx-log.local-spec]
    [blaze.executors :as ex]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [deftest is testing]]
    [cognitect.anomalies :as anom]
    [juxt.iota :refer [given]]
    [taoensso.timbre :as log])
  (:import
    [java.time Clock Duration Instant ZoneId]))


(defn fixture [f]
  (st/instrument)
  (log/set-level! :trace)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


(def ^:private search-param-registry (sr/init-search-param-registry))


(def ^:private resource-indexer-executor
  (ex/cpu-bound-pool "resource-indexer-%d"))


;; TODO: with this shared executor, it's not possible to run test in parallel
(def ^:private local-tx-log-executor
  (ex/single-thread-executor "local-tx-log"))


;; TODO: with this shared executor, it's not possible to run test in parallel
(def ^:private indexer-executor
  (ex/single-thread-executor "indexer"))


(defn new-index-kv-store []
  (new-mem-kv-store
    {:search-param-value-index nil
     :resource-value-index nil
     :compartment-search-param-value-index nil
     :compartment-resource-type-index nil
     :active-search-params nil
     :tx-success-index nil
     :tx-error-index nil
     :t-by-instant-index nil
     :resource-as-of-index nil
     :type-as-of-index nil
     :system-as-of-index nil
     :type-stats-index nil
     :system-stats-index nil}))


(def clock (Clock/fixed Instant/EPOCH (ZoneId/of "UTC")))


(defn new-node-with [{:keys [resource-store]}]
  (let [tx-log (new-local-tx-log (new-mem-kv-store) clock local-tx-log-executor)]
    (node/new-node tx-log resource-indexer-executor 1 indexer-executor
                   (new-index-kv-store) resource-store search-param-registry
                   (Duration/ofMillis 10))))


(defn new-node []
  (new-node-with
    {:resource-store (new-kv-resource-store (new-mem-kv-store))}))


(defn new-resource-store-failing-on-get []
  (reify
    rs/ResourceLookup
    (-get [_ _]
      (ac/failed-future (ex-info "" {::anom/category ::anom/fault})))
    (-multi-get [_ _]
      (ac/failed-future (ex-info "" {::anom/category ::anom/fault})))
    rs/ResourceStore
    (-put [_ _]
      (ac/completed-future nil))))


(defn new-resource-store-failing-on-put []
  (reify
    rs/ResourceLookup
    rs/ResourceStore
    (-put [_ _]
      (ac/failed-future (ex-info "" {::anom/category ::anom/fault})))))


(deftest transact
  (testing "create"
    (testing "one Patient"
      (with-open [node (new-node)]
        @(d/transact node [[:create {:fhir/type :fhir/Patient :id "0"}]])

        (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :create)))

    (testing "one Patient with one Observation"
      (with-open [node (new-node)]
        @(d/transact
           node
           ;; the create ops are purposely disordered in order to test the
           ;; reference dependency ordering algorithm
           [[:create
             {:fhir/type :fhir/Observation :id "0"
              :subject
              {:fhir/type :fhir/Reference
               :reference "Patient/0"}}]
            [:create {:fhir/type :fhir/Patient :id "0"}]])

        (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :create)

        (given @(d/pull node (d/resource-handle (d/db node) "Observation" "0"))
          :fhir/type := :fhir/Observation
          :id := "0"
          [:subject :reference] := "Patient/0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :create))))

  (testing "put"
    (testing "one Patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

        (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :put)))

    (testing "one Patient with one Observation"
      (with-open [node (new-node)]
        @(d/transact
           node
           ;; the create ops are purposely disordered in order to test the
           ;; reference dependency ordering algorithm
           [[:put {:fhir/type :fhir/Observation :id "0"
                   :subject {:fhir/type :fhir/Reference :reference "Patient/0"}}]
            [:put {:fhir/type :fhir/Patient :id "0"}]])

        (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :put)

        (given @(d/pull node (d/resource-handle (d/db node) "Observation" "0"))
          :fhir/type := :fhir/Observation
          :id := "0"
          [:subject :reference] := "Patient/0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :put)))

    (testing "Diamond Reference Dependencies"
      (with-open [node (new-node)]
        @(d/transact
           node
           ;; the create ops are purposely disordered in order to test the
           ;; reference dependency ordering algorithm
           [[:put {:fhir/type :fhir/List
                   :id "0"
                   :entry
                   [{:fhir/type :fhir.List/entry
                     :item
                     {:fhir/type :fhir/Reference
                      :reference "Observation/0"}}
                    {:fhir/type :fhir.List/entry
                     :item
                     {:fhir/type :fhir/Reference
                      :reference "Observation/1"}}]}]
            [:put {:fhir/type :fhir/Observation :id "0"
                   :subject {:fhir/type :fhir/Reference :reference "Patient/0"}}]
            [:put {:fhir/type :fhir/Observation :id "1"
                   :subject {:fhir/type :fhir/Reference :reference "Patient/0"}}]
            [:put {:fhir/type :fhir/Patient :id "0"}]])

        (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :put)

        (given @(d/pull node (d/resource-handle (d/db node) "Observation" "0"))
          :fhir/type := :fhir/Observation
          :id := "0"
          [:subject :reference] := "Patient/0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :put)

        (given @(d/pull node (d/resource-handle (d/db node) "Observation" "1"))
          :fhir/type := :fhir/Observation
          :id := "1"
          [:subject :reference] := "Patient/0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :put)

        (given @(d/pull node (d/resource-handle (d/db node) "List" "0"))
          :fhir/type := :fhir/List
          :id := "0"
          [:entry 0 :item :reference] := "Observation/0"
          [:entry 1 :item :reference] := "Observation/1"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/op] := :put))))

  (testing "a transaction with duplicate resources fails"
    (testing "two puts"
      (with-open [node (new-node)]
        (try
          @(d/transact
             node
             [[:put {:fhir/type :fhir/Patient :id "0"}]
              [:put {:fhir/type :fhir/Patient :id "0"}]])
          (catch Exception e
            (given (ex-data (ex-cause e))
              ::anom/category := ::anom/incorrect
              ::anom/message := "Duplicate resource `Patient/0`.")))))

    (testing "one put and one delete"
      (with-open [node (new-node)]
        (try
          @(d/transact
             node
             [[:put {:fhir/type :fhir/Patient :id "0"}]
              [:delete "Patient" "0"]])
          (catch Exception e
            (given (ex-data (ex-cause e))
              ::anom/category := ::anom/incorrect
              ::anom/message := "Duplicate resource `Patient/0`."))))))

  (testing "a transaction violating referential integrity fails"
    (testing "creating an Observation were the subject doesn't exist"
      (testing "create"
        (with-open [node (new-node)]
          (try
            @(d/transact
               node
               [[:create {:fhir/type :fhir/Observation :id "0"
                          :subject
                          {:fhir/type :fhir/Reference
                           :reference "Patient/0"}}]])
            (catch Exception e
              (given (ex-data (ex-cause e))
                ::anom/category := ::anom/conflict
                ::anom/message := "Referential integrity violated. Resource `Patient/0` doesn't exist.")))))

      (testing "put"
        (with-open [node (new-node)]
          (try
            @(d/transact
               node
               [[:put {:fhir/type :fhir/Observation :id "0"
                       :subject
                       {:fhir/type :fhir/Reference
                        :reference "Patient/0"}}]])
            (catch Exception e
              (given (ex-data (ex-cause e))
                ::anom/category := ::anom/conflict
                ::anom/message := "Referential integrity violated. Resource `Patient/0` doesn't exist."))))))

    (testing "creating a List were the entry item will be deleted in the same transaction"
      (with-open [node (new-node)]
        @(d/transact
           node
           [[:create {:fhir/type :fhir/Observation :id "0"}]
            [:create {:fhir/type :fhir/Observation :id "1"}]])

        (try
          @(d/transact
             node
             [[:create
               {:fhir/type :fhir/List :id "0"
                :entry
                [{:fhir/type :fhir.List/entry
                  :item
                  {:fhir/type :fhir/Reference
                   :reference "Observation/0"}}
                 {:fhir/type :fhir.List/entry
                  :item
                  {:fhir/type :fhir/Reference
                   :reference "Observation/1"}}]}]
              [:delete "Observation" "1"]])
          (catch Exception e
            (given (ex-data (ex-cause e))
              ::anom/category := ::anom/conflict
              ::anom/message := "Referential integrity violated. Resource `Observation/1` should be deleted but is referenced from `List/0`."))))))

  (testing "with failing resource storage"
    (testing "on put"
      (with-open [node (new-node-with
                         {:resource-store (new-resource-store-failing-on-put)})]

        (try
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
          (catch Exception e
            (given (ex-data (ex-cause e))
              ::anom/category := ::anom/fault)))))

    (testing "on get"
      (with-open [node (new-node-with
                         {:resource-store (new-resource-store-failing-on-get)})]

        (try
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
          (catch Exception e
            (given (ex-data (ex-cause e))
              ::anom/category := ::anom/fault)))))))


(deftest tx
  (with-open [node (new-node)]
    @(d/transact node [[:put {:fhir/type :fhir/Patient :id "id-142136"}]])

    (let [db (d/db node)]
      (given (d/tx db (d/basis-t db))
        :blaze.db.tx/instant := Instant/EPOCH))))



;; ---- Instance-Level Functions ----------------------------------------------

(deftest deleted?
  (testing "a node with a patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "id-142136"}]])

      (testing "deleted returns false"
        (is (false? (d/deleted? (d/resource-handle (d/db node) "Patient" "id-142136")))))))

  (testing "a node with a deleted patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "id-141820"}]])
      @(d/transact node [[:delete "Patient" "id-141820"]])

      (testing "deleted returns true"
        (is (true? (d/deleted? (d/resource-handle (d/db node) "Patient" "id-141820"))))))))


(deftest resource
  (testing "a new node does not contain a resource"
    (with-open [node (new-node)]
      (is (nil? (d/resource-handle (d/db node) "Patient" "foo")))))

  (testing "a node contains a resource after a create transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:create {:fhir/type :fhir/Patient :id "0"}]])

      (testing "pull"
        (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
          :fhir/type := :fhir/Patient
          :id := "0"
          [:meta :versionId] := #fhir/id"1"
          [meta :blaze.db/tx :blaze.db/t] := 1
          [meta :blaze.db/num-changes] := 1))

      (testing "pull-content"
        (given @(d/pull-content node (d/resource-handle (d/db node) "Patient" "0"))
          :fhir/type := :fhir/Patient
          :id := "0"))))

  (testing "a node contains a resource after a put transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

      (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
        :fhir/type := :fhir/Patient
        :id := "0"
        [:meta :versionId] := #fhir/id"1"
        [meta :blaze.db/tx :blaze.db/t] := 1
        [meta :blaze.db/num-changes] := 1)))

  (testing "a deleted resource is flagged"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:delete "Patient" "0"]])

      (given @(d/pull node (d/resource-handle (d/db node) "Patient" "0"))
        :fhir/type := :fhir/Patient
        :id := "0"
        [:meta :versionId] := #fhir/id"2"
        [meta :blaze.db/op] := :delete
        [meta :blaze.db/tx :blaze.db/t] := 2))))



;; ---- Type-Level Functions --------------------------------------------------

(deftest list-resources-and-type-total
  (testing "a new node has no patients"
    (with-open [node (new-node)]
      (is (coll/empty? (d/list-resource-handles (d/db node) "Patient")))
      (is (zero? (d/type-total (d/db node) "Patient")))))

  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

      (testing "has one list entry"
        (is (= 1 (count (d/list-resource-handles (d/db node) "Patient"))))
        (is (= 1 (d/type-total (d/db node) "Patient"))))

      (testing "contains that patient"
        (given @(d/pull-many node (d/list-resource-handles (d/db node) "Patient"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :fhir/type] := :fhir/Meta
          [0 :meta :versionId] := #fhir/id"1"
          [0 :meta :lastUpdated] := Instant/EPOCH))))

  (testing "a node with one deleted patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:delete "Patient" "0"]])

      (testing "doesn't contain it in the list"
        (is (coll/empty? (d/list-resource-handles (d/db node) "Patient")))
        (is (zero? (d/type-total (d/db node) "Patient"))))))

  (testing "a node with two patients in two transactions"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "1"}]])

      (testing "has two list entries"
        (is (= 2 (count (d/list-resource-handles (d/db node) "Patient"))))
        (is (= 2 (d/type-total (d/db node) "Patient"))))

      (testing "contains both patients in id order"
        (given @(d/pull-many node (d/list-resource-handles (d/db node) "Patient"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "1"
          [1 :meta :versionId] := #fhir/id"2"))

      (testing "it is possible to start with the second patient"
        (given @(d/pull-many node (d/list-resource-handles (d/db node) "Patient" "1"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "1"
          [0 :meta :versionId] := #fhir/id"2"
          1 := nil))

      (testing "a nil start-id gives the full result"
        (given @(d/pull-many node (d/list-resource-handles (d/db node) "Patient" nil))
          [0 :id] := "0"
          [1 :id] := "1"))

      (testing "overshooting the start-id returns an empty collection"
        (is (coll/empty? (d/list-resource-handles (d/db node) "Patient" "2"))))))

  (testing "a node with two patients in one transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]
                         [:put {:fhir/type :fhir/Patient :id "1"}]])

      (testing "has two list entries"
        (is (= 2 (count (d/list-resource-handles (d/db node) "Patient"))))
        (is (= 2 (d/type-total (d/db node) "Patient"))))

      (testing "contains both patients in id order"
        (given @(d/pull-many node (d/list-resource-handles (d/db node) "Patient"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "1"
          [1 :meta :versionId] := #fhir/id"1"))

      (testing "it is possible to start with the second patient"
        (given @(d/pull-many node (d/list-resource-handles (d/db node) "Patient" "1"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "1"
          [0 :meta :versionId] := #fhir/id"1"
          1 := nil))

      (testing "overshooting the start-id returns an empty collection"
        (is (coll/empty? (d/list-resource-handles (d/db node) "Patient" "2"))))))

  (testing "a node with one updated patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active false}]])
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

      (testing "has one list entry"
        (is (= 1 (count (d/list-resource-handles (d/db node) "Patient"))))
        (is (= 1 (d/type-total (d/db node) "Patient"))))

      (testing "contains the updated patient"
        (given @(d/pull-many node (d/list-resource-handles (d/db node) "Patient"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :active] := true
          [0 :meta :versionId] := #fhir/id"2"))))

  (testing "a node with resources of different types"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "0"}]])

      (testing "has one patient list entry"
        (is (= 1 (count (d/list-resource-handles (d/db node) "Patient"))))
        (is (= 1 (d/type-total (d/db node) "Patient"))))

      (testing "has one observation list entry"
        (is (= 1 (count (d/list-resource-handles (d/db node) "Observation"))))
        (is (= 1 (d/type-total (d/db node) "Observation"))))))

  (testing "the database is immutable"
    (testing "while updating a patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active false}]])

        (let [db (d/db node)]
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

          (testing "the original database"
            (testing "has still only one list entry"
              (is (= 1 (count (d/list-resource-handles db "Patient"))))
              (is (= 1 (d/type-total db "Patient"))))

            (testing "contains still the original patient"
              (given @(d/pull-many node (d/list-resource-handles db "Patient"))
                [0 :fhir/type] := :fhir/Patient
                [0 :id] := "0"
                [0 :active] := false
                [0 :meta :versionId] := #fhir/id"1"))))))

    (testing "while adding another patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

        (let [db (d/db node)]
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "1"}]])

          (testing "the original database"
            (testing "has still only one list entry"
              (is (= 1 (count (d/list-resource-handles db "Patient"))))
              (is (= 1 (d/type-total db "Patient"))))

            (testing "contains still the first patient"
              (given @(d/pull-many node (d/list-resource-handles db "Patient"))
                [0 :fhir/type] := :fhir/Patient
                [0 :id] := "0"
                [0 :meta :versionId] := #fhir/id"1"))))))))


(defn- pull-type-query
  ([node type clauses]
   (d/pull-many node (d/type-query (d/db node) type clauses)))
  ([node type clauses start-id]
   (d/pull-many node (d/type-query (d/db node) type clauses start-id))))


(deftest type-query
  (testing "a new node has no patients"
    (with-open [node (new-node)]
      (is (coll/empty? (d/type-query (d/db node) "Patient" [["gender" "male"]])))))

  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

      (testing "the patient can be found"
        (given @(pull-type-query node "Patient" [["active" "true"]])
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"))

      (testing "an unknown search-param errors"
        (given (d/type-query (d/db node) "Patient" [["foo" "bar"]
                                                    ["active" "true"]])
          ::anom/category := ::anom/not-found
          ::anom/message := "search-param with code `foo` and type `Patient` not found")

        (testing "with start id"
          (given (d/type-query (d/db node) "Patient" [["foo" "bar"]
                                                      ["active" "true"]]
                               "0")
            ::anom/category := ::anom/not-found
            ::anom/message := "search-param with code `foo` and type `Patient` not found")))))

  (testing "a node with two patients in one transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]
                         [:put {:fhir/type :fhir/Patient :id "1" :active false}]])

      (testing "only the active patient will be found"
        (given @(pull-type-query node "Patient" [["active" "true"]])
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          1 := nil))

      (testing "only the non-active patient will be found"
        (given @(pull-type-query node "Patient" [["active" "false"]])
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "1"
          1 := nil))

      (testing "both patients will be found"
        (given @(pull-type-query node "Patient" [["active" "true" "false"]])
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "1"))))

  (testing "does not find the deleted male patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]
                         [:put {:fhir/type :fhir/Patient :id "1" :active true}]])
      @(d/transact node [[:delete "Patient" "1"]])

      (given @(pull-type-query node "Patient" [["active" "true"]])
        [0 :fhir/type] := :fhir/Patient
        [0 :id] := "0"
        1 := nil)))

  (testing "a node with three patients in one transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]
                         [:put {:fhir/type :fhir/Patient :id "1" :active false}]
                         [:put {:fhir/type :fhir/Patient :id "2" :active true}]])

      (testing "two active patients will be found"
        (given @(pull-type-query node "Patient" [["active" "true"]])
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "2"
          2 := nil))

      (testing "it is possible to start with the second patient"
        (given @(pull-type-query node "Patient" [["active" "true"]] "2")
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "2"
          1 := nil))

      (testing "a nil start-id gives the full result"
        (given @(pull-type-query node "Patient" [["active" "true"]] nil)
          [0 :id] := "0"
          [1 :id] := "2"
          2 := nil))))

  (testing "Special Search Parameter _list"
    (testing "a node with two patients, one observation and one list in one transaction"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]
                           [:put {:fhir/type :fhir/Patient :id "1"}]
                           [:put {:fhir/type :fhir/Observation :id "0"}]
                           [:put {:fhir/type :fhir/List :id "0"
                                  :entry
                                  [{:fhir/type :fhir.List/entry
                                    :item
                                    {:fhir/type :fhir/Reference
                                     :reference "Patient/0"}}
                                   {:fhir/type :fhir.List/entry
                                    :item
                                    {:fhir/type :fhir/Reference
                                     :reference "Observation/0"}}]}]])

        (testing "returns only the patient referenced in the list"
          (given @(pull-type-query node "Patient" [["_list" "0"]])
            [0 :fhir/type] := :fhir/Patient
            [0 :id] := "0"
            1 := nil))

        (testing "returns only the observation referenced in the list"
          (given @(pull-type-query node "Observation" [["_list" "0"]])
            [0 :fhir/type] := :fhir/Observation
            [0 :id] := "0"
            1 := nil))))

    (testing "a node with three patients and one list in one transaction"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]
                           [:put {:fhir/type :fhir/Patient :id "1"}]
                           [:put {:fhir/type :fhir/Patient :id "2"}]
                           [:put {:fhir/type :fhir/Patient :id "3"}]
                           [:put {:fhir/type :fhir/List :id "0"
                                  :entry
                                  [{:fhir/type :fhir.List/entry
                                    :item
                                    {:fhir/type :fhir/Reference
                                     :reference "Patient/0"}}
                                   {:fhir/type :fhir.List/entry
                                    :item
                                    {:fhir/type :fhir/Reference
                                     :reference "Patient/2"}}
                                   {:fhir/type :fhir.List/entry
                                    :item
                                    {:fhir/type :fhir/Reference
                                     :reference "Patient/3"}}]}]])

        (testing "it is possible to start with the second patient"
          (given @(pull-type-query node "Patient" [["_list" "0"]] "2")
            [0 :id] := "2"
            [1 :id] := "3"
            2 := nil))

        (testing "a nil start-id gives the full result"
          (given @(pull-type-query node "Patient" [["_list" "0"]] nil)
            [0 :id] := "0"
            [1 :id] := "2"
            [2 :id] := "3"
            3 := nil)))))

  (testing "Patient"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Patient
                 :id "id-0"
                 :meta
                 {:fhir/type :fhir/Meta
                  :profile [#fhir/canonical"profile-uri-145024"]}
                 :identifier
                 [{:fhir/type :fhir/Identifier
                   :value "0"}]
                 :active false
                 :gender #fhir/code"male"
                 :birthDate #fhir/date"2020-02-08"
                 :deceased true
                 :address
                 [{:fhir/type :fhir/Address
                   :line ["Philipp-Rosenthal-Straße 27"]
                   :city "Leipzig"}]
                 :name
                 [{:fhir/type :fhir/HumanName
                   :family "Müller"}]}]
          [:put {:fhir/type :fhir/Patient
                 :id "id-1"
                 :active true
                 :gender #fhir/code"female"
                 :birthDate #fhir/date"2020-02"
                 :address
                 [{:fhir/type :fhir/Address
                   :city "Berlin"}]
                 :telecom
                 [{:fhir/type :fhir/ContactPoint
                   :system #fhir/code"email"
                   :value "foo@bar.baz"}
                  {:fhir/type :fhir/ContactPoint
                   :system #fhir/code"phone"
                   :value "0815"}]}]
          [:put {:fhir/type :fhir/Patient
                 :id "id-2"
                 :active false
                 :gender #fhir/code"female"
                 :birthDate #fhir/date"2020"
                 :deceased #fhir/dateTime"2020-03"
                 :address
                 [{:fhir/type :fhir/Address
                   :line ["Liebigstraße 20a"]
                   :city "Leipzig"}]
                 :name
                 [{:fhir/type :fhir/HumanName
                   :family "Schmidt"}]}]])

      (testing "_id"
        (given @(pull-type-query node "Patient" [["_id" "id-1"]])
          [0 :id] := "id-1"
          1 := nil))

      (testing "_profile"
        (given @(pull-type-query node "Patient" [["_profile" "profile-uri-145024"]])
          [0 :id] := "id-0"
          1 := nil))

      (testing "active"
        (given @(pull-type-query node "Patient" [["active" "true"]])
          [0 :id] := "id-1"
          1 := nil))

      (testing "address with line"
        (testing "in first position"
          (given @(pull-type-query node "Patient" [["address" "Liebigstraße"]])
            [0 :id] := "id-2"
            1 := nil))

        (testing "in second position"
          (given @(pull-type-query node "Patient" [["gender" "female"]
                                                   ["address" "Liebigstraße"]])
            [0 :id] := "id-2"
            1 := nil)))

      (testing "address with city"
        (testing "full result"
          (given @(pull-type-query node "Patient" [["address" "Leipzig"]])
            [0 :id] := "id-0"
            [1 :id] := "id-2"
            2 := nil))

        (testing "it is possible to start with the second patient"
          (given @(pull-type-query node "Patient" [["address" "Leipzig"]] "id-2")
            [0 :id] := "id-2"
            1 := nil)))

      (testing "address-city full"
        (given @(pull-type-query node "Patient" [["address-city" "Leipzig"]])
          [0 :id] := "id-0"
          [1 :id] := "id-2"
          2 := nil))

      (testing "address-city prefix"
        (testing "full result"
          (given @(pull-type-query node "Patient" [["address-city" "Leip"]])
            [0 :id] := "id-0"
            [1 :id] := "id-2"
            2 := nil))

        (testing "it is possible to start with the second patient"
          (given @(pull-type-query node "Patient" [["address-city" "Leip"]] "id-2")
            [0 :id] := "id-2"
            1 := nil)))

      (testing "address-city and family prefix"
        (given @(pull-type-query node "Patient" [["address-city" "Leip"]
                                                 ["family" "Sch"]])
          [0 :id] := "id-2"
          1 := nil))

      (testing "address-city and gender"
        (given @(pull-type-query node "Patient" [["address-city" "Leipzig"]
                                                 ["gender" "female"]])
          [0 :id] := "id-2"
          1 := nil))

      (testing "birthdate YYYYMMDD"
        (given @(pull-type-query node "Patient" [["birthdate" "2020-02-08"]])
          [0 :id] := "id-0"
          1 := nil))

      (testing "birthdate YYYYMM"
        (testing "full result"
          (given @(pull-type-query node "Patient" [["birthdate" "2020-02"]])
            [0 :id] := "id-1"
            [1 :id] := "id-0"
            2 := nil))

        (testing "it is possible to start with the second patient"
          (given @(pull-type-query node "Patient" [["birthdate" "2020-02"]] "id-0")
            [0 :id] := "id-0"
            1 := nil)))

      (testing "birthdate YYYY"
        (given @(pull-type-query node "Patient" [["birthdate" "2020"]])
          [0 :id] := "id-2"
          [1 :id] := "id-1"
          [2 :id] := "id-0"))

      (testing "birthdate with `eq` prefix"
        (given @(pull-type-query node "Patient" [["birthdate" "eq2020-02-08"]])
          [0 :id] := "id-0"
          1 := nil))

      (testing "birthdate with `ne` prefix is unsupported"
        (try
          @(pull-type-query node "Patient" [["birthdate" "ne2020-02-08"]])
          (catch Exception e
            (given (ex-data e)
              ::anom/category := ::anom/unsupported))))

      (testing "birthdate with `ge` prefix"
        (testing "finds equal date"
          (given @(pull-type-query node "Patient" [["birthdate" "ge2020-02-08"]])
            [0 :id] := "id-0"
            [0 :birthDate] := #fhir/date"2020-02-08"
            1 := nil))

        (testing "finds greater date"
          (given @(pull-type-query node "Patient" [["birthdate" "ge2020-02-07"]])
            [0 :id] := "id-0"
            [0 :birthDate] := #fhir/date"2020-02-08"
            1 := nil))

        (testing "finds more precise dates"
          (given @(pull-type-query node "Patient" [["birthdate" "ge2020-02"]])
            [0 :id] := "id-1"
            [0 :birthDate] := #fhir/date"2020-02"
            [1 :id] := "id-0"
            [1 :birthDate] := #fhir/date"2020-02-08"
            2 := nil)))

      (testing "birthdate with `le` prefix"
        (testing "finds equal date"
          (given @(pull-type-query node "Patient" [["birthdate" "le2020-02-08"]])
            [0 :id] := "id-0"
            [0 :birthDate] := #fhir/date"2020-02-08"
            1 := nil))

        (testing "finds less date"
          (given @(pull-type-query node "Patient" [["birthdate" "le2020-02-09"]])
            [0 :id] := "id-0"
            [0 :birthDate] := #fhir/date"2020-02-08"
            1 := nil))

        (testing "finds more precise dates"
          (given @(pull-type-query node "Patient" [["birthdate" "le2020-03"]])
            [0 :id] := "id-1"
            [0 :birthDate] := #fhir/date"2020-02"
            [1 :id] := "id-0"
            [1 :birthDate] := #fhir/date"2020-02-08"
            2 := nil)))

      (testing "deceased"
        (given @(pull-type-query node "Patient" [["deceased" "true"]])
          [0 :id] := "id-0"
          [1 :id] := "id-2"
          2 := nil))

      (testing "email"
        (given @(pull-type-query node "Patient" [["email" "foo@bar.baz"]])
          [0 :id] := "id-1"
          1 := nil))

      (testing "family lower-case"
        (given @(pull-type-query node "Patient" [["family" "schmidt"]])
          [0 :id] := "id-2"
          1 := nil))

      (testing "gender"
        (given @(pull-type-query node "Patient" [["gender" "male"]])
          [0 :id] := "id-0"
          1 := nil))

      (testing "identifier"
        (given @(pull-type-query node "Patient" [["identifier" "0"]])
          [0 :id] := "id-0"
          1 := nil))

      (testing "telecom"
        (given @(pull-type-query node "Patient" [["telecom" "0815"]])
          [0 :id] := "id-1"
          1 := nil))))

  (testing "Practitioner"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Practitioner
                 :id "id-0"
                 :name
                 [{:fhir/type :fhir/HumanName
                   :family "Müller"
                   :given ["Hans" "Martin"]}]}]])

      (testing "name"
        (testing "using family"
          (given @(pull-type-query node "Practitioner" [["name" "müller"]])
            [0 :id] := "id-0"
            1 := nil))

        (testing "using first given"
          (given @(pull-type-query node "Practitioner" [["name" "hans"]])
            [0 :id] := "id-0"
            1 := nil))

        (testing "using second given"
          (given @(pull-type-query node "Practitioner" [["name" "martin"]])
            [0 :id] := "id-0"
            1 := nil)))))

  (testing "Specimen"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Specimen
                 :id "id-0"
                 :type
                 {:fhir/type :fhir/CodeableConcept
                  :coding
                  [{:fhir/type :fhir/Coding
                    :system #fhir/uri"https://fhir.bbmri.de/CodeSystem/SampleMaterialType"
                    :code #fhir/code"dna"}]}
                 :collection
                 {:fhir/type :fhir.Specimen/collection
                  :bodySite
                  {:fhir/type :fhir/CodeableConcept
                   :coding
                   [{:fhir/type :fhir/Coding
                     :system #fhir/uri"urn:oid:2.16.840.1.113883.6.43.1"
                     :code #fhir/code"C77.4"}]}}}]])

      (testing "bodysite"
        (testing "using system|code"
          (given @(pull-type-query node "Specimen" [["bodysite" "urn:oid:2.16.840.1.113883.6.43.1|C77.4"]])
            [0 :id] := "id-0"
            1 := nil))

        (testing "using code"
          (given @(pull-type-query node "Specimen" [["bodysite" "C77.4"]])
            [0 :id] := "id-0"
            1 := nil))

        (testing "using system|"
          (given @(pull-type-query node "Specimen" [["bodysite" "urn:oid:2.16.840.1.113883.6.43.1|"]])
            [0 :id] := "id-0"
            1 := nil)))

      (testing "type"
        (given @(pull-type-query node "Specimen" [["type" "https://fhir.bbmri.de/CodeSystem/SampleMaterialType|dna"]])
          [0 :id] := "id-0"
          1 := nil))

      (testing "bodysite and type"
        (testing "using system|code"
          (given @(pull-type-query node "Specimen" [["bodysite" "urn:oid:2.16.840.1.113883.6.43.1|C77.4"]
                                                    ["type" "https://fhir.bbmri.de/CodeSystem/SampleMaterialType|dna"]])
            [0 :id] := "id-0"
            1 := nil))

        (testing "using code"
          (given @(pull-type-query node "Specimen" [["bodysite" "urn:oid:2.16.840.1.113883.6.43.1|C77.4"]
                                                    ["type" "dna"]])
            [0 :id] := "id-0"
            1 := nil))

        (testing "using system|"
          (given @(pull-type-query node "Specimen" [["bodysite" "urn:oid:2.16.840.1.113883.6.43.1|C77.4"]
                                                    ["type" "https://fhir.bbmri.de/CodeSystem/SampleMaterialType|"]])
            [0 :id] := "id-0"
            1 := nil))

        (testing "does not match"
          (testing "using system|code"
            (given @(pull-type-query node "Specimen" [["bodysite" "urn:oid:2.16.840.1.113883.6.43.1|C77.4"]
                                                      ["type" "https://fhir.bbmri.de/CodeSystem/SampleMaterialType|urine"]])
              0 := nil))))))

  (testing "ActivityDefinition"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/ActivityDefinition
                 :id "id-0"
                 :url #fhir/uri"url-111619"
                 :description #fhir/markdown"desc-121208"}]
          [:put {:fhir/type :fhir/ActivityDefinition
                 :id "id-1"
                 :url #fhir/uri"url-111721"}]])

      (testing "url"
        (given @(pull-type-query node "ActivityDefinition" [["url" "url-111619"]])
          [0 :id] := "id-0"
          1 := nil))

      (testing "description"
        (given @(pull-type-query node "ActivityDefinition" [["description" "desc-121208"]])
          [0 :id] := "id-0"
          1 := nil))))

  (testing "CodeSystem"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/CodeSystem
                 :id "id-0"
                 :version "version-122443"}]
          [:put {:fhir/type :fhir/CodeSystem
                 :id "id-1"
                 :version "version-122456"}]])

      (testing "version"
        (given @(pull-type-query node "CodeSystem" [["version" "version-122443"]])
          [0 :id] := "id-0"
          1 := nil))))

  (testing "MedicationKnowledge"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/MedicationKnowledge
                 :id "id-0"
                 :monitoringProgram
                 [{:fhir/type :fhir.MedicationKnowledge/monitoringProgram
                   :name "name-123124"}]}]
          [:put {:fhir/type :fhir/MedicationKnowledge
                 :id "id-1"}]])

      (testing "monitoring-program-name"
        (given @(pull-type-query node "MedicationKnowledge" [["monitoring-program-name" "name-123124"]])
          [0 :id] := "id-0"
          1 := nil))))

  (testing "Condition"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Patient
                 :id "id-0"}]
          [:put {:fhir/type :fhir/Condition
                 :id "id-0"
                 :subject
                 {:fhir/type :fhir/Reference
                  :reference "Patient/id-0"}}]
          [:put {:fhir/type :fhir/Condition
                 :id "id-1"}]])

      (testing "patient"
        (given @(pull-type-query node "Condition" [["patient" "id-0"]])
          [0 :id] := "id-0"
          1 := nil))))

  (testing "Observation"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Observation
                 :id "id-0"
                 :status #fhir/code"final"
                 :value
                 {:fhir/type :fhir/Quantity
                  :value 23.42M
                  :unit "kg/m²"
                  :code #fhir/code"kg/m2"
                  :system #fhir/uri"http://unitsofmeasure.org"}}]])

      (testing "value-quantity"
        (testing "without unit"
          (let [clauses [["value-quantity" "23.42"]]]
            (given @(pull-type-query node "Observation" clauses)
              [0 :id] := "id-0"
              1 := nil)))

        (testing "with minimal unit"
          (let [clauses [["value-quantity" "23.42|kg/m2"]]]
            (given @(pull-type-query node "Observation" clauses)
              [0 :id] := "id-0"
              1 := nil)))

        (testing "with human unit"
          (let [clauses [["value-quantity" "23.42|kg/m²"]]]
            (given @(pull-type-query node "Observation" clauses)
              [0 :id] := "id-0"
              1 := nil)))

        (testing "with full unit"
          (let [clauses [["value-quantity" "23.42|http://unitsofmeasure.org|kg/m2"]]]
            (given @(pull-type-query node "Observation" clauses)
              [0 :id] := "id-0"
              1 := nil))))

      (testing "status and value-quantity"
        (let [clauses [["status" "final"] ["value-quantity" "23.42|kg/m2"]]]
          (given @(pull-type-query node "Observation" clauses)
            [0 :id] := "id-0"
            1 := nil)))

      (testing "value-quantity and status"
        (let [clauses [["value-quantity" "23.42|kg/m2"] ["status" "final"]]]
          (given @(pull-type-query node "Observation" clauses)
            [0 :id] := "id-0"
            1 := nil)))))

  (testing "Observation"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Observation
                 :id "id-0"
                 :status #fhir/code"final"
                 :value
                 {:fhir/type :fhir/Quantity
                  :value 23.42M
                  :unit "kg/m²"
                  :code #fhir/code"kg/m2"
                  :system #fhir/uri"http://unitsofmeasure.org"}}]
          [:put {:fhir/type :fhir/Observation
                 :id "id-1"
                 :status #fhir/code"final"
                 :value
                 {:fhir/type :fhir/Quantity
                  :value 23.42M
                  :unit "kg/m²"
                  :code #fhir/code"kg/m2"
                  :system #fhir/uri"http://unitsofmeasure.org"}}]])

      (testing "full result"
        (let [clauses [["value-quantity" "23.42"]]]
          (given @(pull-type-query node "Observation" clauses)
            [0 :id] := "id-0"
            [1 :id] := "id-1"
            2 := nil)))

      (testing "it is possible to start with the second patient"
        (let [clauses [["value-quantity" "23.42"]]]
          (given @(pull-type-query node "Observation" clauses "id-1")
            [0 :id] := "id-1"
            1 := nil)))))

  (testing "MeasureReport"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/MeasureReport
                 :id "id-144132"
                 :measure #fhir/canonical"measure-url-181106"}]])

      (testing "measure"
        (let [clauses [["measure" "measure-url-181106"]]]
          (given @(pull-type-query node "MeasureReport" clauses)
            [0 :id] := "id-144132"
            1 := nil)))))

  (testing "List"
    (testing "item"
      (testing "with no modifier"
        (with-open [node (new-node)]
          @(d/transact
             node
             [[:put {:fhir/type :fhir/Patient :id "0"}]
              [:put {:fhir/type :fhir/Patient :id "1"}]])
          @(d/transact
             node
             [[:put {:fhir/type :fhir/List
                     :id "id-150545"
                     :entry
                     [{:fhir/type :fhir.List/entry
                       :item
                       {:fhir/type :fhir/Reference
                        :reference "Patient/0"}}]}]
              [:put {:fhir/type :fhir/List
                     :id "id-143814"
                     :entry
                     [{:fhir/type :fhir.List/entry
                       :item
                       {:fhir/type :fhir/Reference
                        :reference "Patient/1"}}]}]])

          (let [clauses [["item" "Patient/1"]]]
            (given @(pull-type-query node "List" clauses)
              [0 :id] := "id-143814"
              1 := nil))))

      (testing "with identifier modifier"
        (with-open [node (new-node)]
          @(d/transact
             node
             [[:put {:fhir/type :fhir/List
                     :id "id-123058"
                     :entry
                     [{:fhir/type :fhir.List/entry
                       :item
                       {:fhir/type :fhir/Reference
                        :identifier
                        {:fhir/type :fhir/Identifier
                         :system #fhir/uri"system-122917"
                         :value "value-122931"}}}]}]
              [:put {:fhir/type :fhir/List
                     :id "id-143814"
                     :entry
                     [{:fhir/type :fhir.List/entry
                       :item
                       {:fhir/type :fhir/Reference
                        :identifier
                        {:fhir/type :fhir/Identifier
                         :system #fhir/uri"system-122917"
                         :value "value-143818"}}}]}]])

          (let [clauses [["item:identifier" "system-122917|value-122931"]]]
            (given @(pull-type-query node "List" clauses)
              [0 :id] := "id-123058"
              1 := nil)))))

    (testing "code and item"
      (testing "with identifier modifier"
        (with-open [node (new-node)]
          @(d/transact
             node
             [[:put {:fhir/type :fhir/List
                     :id "id-123058"
                     :code
                     {:fhir/type :fhir/CodeableConcept
                      :coding
                      [{:fhir/type :fhir/Coding
                        :system #fhir/uri"system-152812"
                        :code #fhir/code"code-152819"}]}
                     :entry
                     [{:fhir/type :fhir.List/entry
                       :item
                       {:fhir/type :fhir/Reference
                        :identifier
                        {:fhir/type :fhir/Identifier
                         :system #fhir/uri"system-122917"
                         :value "value-122931"}}}]}]
              [:put {:fhir/type :fhir/List
                     :id "id-143814"
                     :code
                     {:fhir/type :fhir/CodeableConcept
                      :coding
                      [{:fhir/type :fhir/Coding
                        :system #fhir/uri"system-152812"
                        :code #fhir/code"code-152819"}]}
                     :entry
                     [{:fhir/type :fhir.List/entry
                       :item
                       {:fhir/type :fhir/Reference
                        :identifier
                        {:fhir/type :fhir/Identifier
                         :system #fhir/uri"system-122917"
                         :value "value-143818"}}}]}]])

          (let [clauses [["code" "system-152812|code-152819"]
                         ["item:identifier" "system-122917|value-143818"]]]
            (given @(pull-type-query node "List" clauses)
              [0 :id] := "id-143814"
              1 := nil)))))))


(deftest compile-type-query
  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

      (testing "the patient can be found"
        (given @(->> (d/compile-type-query node "Patient" [["active" "true"]])
                     (d/execute-query (d/db node))
                     (d/pull-many node))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"))

      (testing "an unknown search-param errors"
        (given (d/compile-type-query node "Patient" [["foo" "bar"]
                                                     ["active" "true"]])
          ::anom/category := ::anom/not-found
          ::anom/message := "search-param with code `foo` and type `Patient` not found")))))


(deftest compile-type-query-lenient
  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

      (testing "the patient can be found"
        (given @(->> (d/compile-type-query-lenient node "Patient" [["active" "true"]])
                     (d/execute-query (d/db node))
                     (d/pull-many node))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"))

      (testing "an unknown search-param is ignored"
        (given @(->> (d/compile-type-query-lenient node "Patient" [["foo" "bar"]
                                                                   ["active" "true"]])
                     (d/execute-query (d/db node))
                     (d/pull-many node))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0")))))



;; ---- System-Level Functions ------------------------------------------------

(deftest system-list-and-total
  (testing "a new node has no resources"
    (with-open [node (new-node)]
      (is (zero? (d/system-total (d/db node))))))

  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

      (testing "has one list entry"
        (is (= 1 (count (d/system-list (d/db node)))))
        (is (= 1 (d/system-total (d/db node)))))

      (testing "contains that patient"
        (given @(d/pull-many node (d/system-list (d/db node)))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"))))

  (testing "a node with one deleted patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:delete "Patient" "0"]])

      (testing "doesn't contain it in the list"
        (is (coll/empty? (d/system-list (d/db node))))
        (is (zero? (d/system-total (d/db node)))))))

  (testing "a node with two resources in two transactions"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "0"}]])

      (testing "has two list entries"
        (is (= 2 (count (d/system-list (d/db node)))))
        (is (= 2 (d/system-total (d/db node)))))

      (testing "contains both resources in the order of their type hashes"
        (given @(d/pull-many node (d/system-list (d/db node)))
          [0 :fhir/type] := :fhir/Observation
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"2"
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "0"
          [1 :meta :versionId] := #fhir/id"1"))

      (testing "it is possible to start with the patient"
        (given @(d/pull-many node (d/system-list (d/db node) "Patient"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"))

      (testing "starting with Measure also returns the patient,
                because in type hash order, Measure comes before
                Patient but after Observation"
        (given @(d/pull-many node (d/system-list (d/db node) "Measure"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"))

      (testing "overshooting the start-id returns an empty collection"
        (is (coll/empty? (d/system-list (d/db node) "Patient" "1")))))))



;; ---- Compartment-Level Functions -------------------------------------------

(deftest list-compartment-resources
  (testing "a new node has an empty list of resources in the Patient/0 compartment"
    (with-open [node (new-node)]
      (is (coll/empty? (d/list-compartment-resource-handles (d/db node) "Patient" "0" "Observation")))))

  (testing "a node contains one Observation in the Patient/0 compartment"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "0"
                                :subject
                                {:fhir/type :fhir/Reference
                                 :reference "Patient/0"}}]])

      (given @(d/pull-many node (d/list-compartment-resource-handles (d/db node) "Patient" "0" "Observation"))
        [0 :fhir/type] := :fhir/Observation
        [0 :id] := "0"
        [0 :meta :versionId] := #fhir/id"2"
        1 := nil)))

  (testing "a node contains two resources in the Patient/0 compartment"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "0"
                                :subject
                                {:fhir/type :fhir/Reference
                                 :reference "Patient/0"}}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "1"
                                :subject
                                {:fhir/type :fhir/Reference
                                 :reference "Patient/0"}}]])

      (given @(d/pull-many node (d/list-compartment-resource-handles (d/db node) "Patient" "0" "Observation"))
        [0 :fhir/type] := :fhir/Observation
        [0 :id] := "0"
        [0 :meta :versionId] := #fhir/id"2"
        [1 :fhir/type] := :fhir/Observation
        [1 :id] := "1"
        [1 :meta :versionId] := #fhir/id"3")))

  (testing "a deleted resource does not show up"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "0"
                                :subject
                                {:fhir/type :fhir/Reference
                                 :reference "Patient/0"}}]])
      @(d/transact node [[:delete "Observation" "0"]])
      (is (coll/empty? (d/list-compartment-resource-handles (d/db node) "Patient" "0" "Observation")))))

  (testing "it is possible to start at a later id"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "0"
                                :subject
                                {:fhir/type :fhir/Reference
                                 :reference "Patient/0"}}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "1"
                                :subject
                                {:fhir/type :fhir/Reference
                                 :reference "Patient/0"}}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "2"
                                :subject
                                {:fhir/type :fhir/Reference
                                 :reference "Patient/0"}}]])

      (given @(d/pull-many node (d/list-compartment-resource-handles (d/db node) "Patient" "0" "Observation" "1"))
        [0 :fhir/type] := :fhir/Observation
        [0 :id] := "1"
        [0 :meta :versionId] := #fhir/id"3"
        [1 :fhir/type] := :fhir/Observation
        [1 :id] := "2"
        [1 :meta :versionId] := #fhir/id"4"
        2 := nil)))

  (testing "Unknown compartment is not a problem"
    (with-open [node (new-node)]
      (is (coll/empty? (d/list-compartment-resource-handles (d/db node) "foo" "bar" "Condition"))))))


(deftest compartment-query
  (testing "a new node has an empty list of resources in the Patient/0 compartment"
    (with-open [node (new-node)]
      (is (coll/empty? (d/compartment-query (d/db node) "Patient" "0" "Observation" [["code" "foo"]])))))

  (testing "returns the Observation in the Patient/0 compartment"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Patient :id "0"}]
          [:put {:fhir/type :fhir/Observation :id "0"
                 :subject
                 {:fhir/type :fhir/Reference
                  :reference "Patient/0"}
                 :code
                 {:fhir/type :fhir/CodeableConcept
                  :coding
                  [{:fhir/type :fhir/Coding
                    :system #fhir/uri"system-191514"
                    :code #fhir/code"code-191518"}]}}]])

      (given @(d/pull-many node
                           (d/compartment-query
                             (d/db node) "Patient" "0" "Observation"
                             [["code" "system-191514|code-191518"]]))
        [0 :fhir/type] := :fhir/Observation
        [0 :id] := "0"
        1 := nil)))

  (testing "returns only the matching Observation in the Patient/0 compartment"
    (let [observation
          (fn [id code]
            {:fhir/type :fhir/Observation :id id
             :subject
             {:fhir/type :fhir/Reference
              :reference "Patient/0"}
             :code
             {:fhir/type :fhir/CodeableConcept
              :coding
              [{:fhir/type :fhir/Coding
                :system #fhir/uri"system"
                :code code}]}})]
      (with-open [node (new-node)]
        @(d/transact
           node
           [[:put {:fhir/type :fhir/Patient :id "0"}]
            [:put (observation "0" #fhir/code"code-1")]
            [:put (observation "1" #fhir/code"code-2")]
            [:put (observation "2" #fhir/code"code-3")]])

        (given @(d/pull-many node
                             (d/compartment-query
                               (d/db node) "Patient" "0" "Observation"
                               [["code" "system|code-2"]]))
          [0 :fhir/type] := :fhir/Observation
          [0 :id] := "1"
          1 := nil))))

  (testing "returns only the matching versions"
    (let [observation
          (fn [id code]
            {:fhir/type :fhir/Observation :id id
             :subject
             {:fhir/type :fhir/Reference
              :reference "Patient/0"}
             :code
             {:fhir/type :fhir/CodeableConcept
              :coding
              [{:fhir/type :fhir/Coding
                :system #fhir/uri"system"
                :code code}]}})]
      (with-open [node (new-node)]
        @(d/transact
           node
           [[:put {:fhir/type :fhir/Patient :id "0"}]
            [:put (observation "0" #fhir/code"code-1")]
            [:put (observation "1" #fhir/code"code-2")]
            [:put (observation "2" #fhir/code"code-2")]
            [:put (observation "3" #fhir/code"code-2")]])
        @(d/transact
           node
           [[:put (observation "0" #fhir/code"code-2")]
            [:put (observation "1" #fhir/code"code-1")]
            [:put (observation "3" #fhir/code"code-2")]])

        (given @(d/pull-many node
                             (d/compartment-query
                               (d/db node) "Patient" "0" "Observation"
                               [["code" "system|code-2"]]))
          [0 :fhir/type] := :fhir/Observation
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"2"
          [1 :fhir/type] := :fhir/Observation
          [1 :id] := "2"
          [1 :meta :versionId] := #fhir/id"1"
          [2 :id] := "3"
          [2 :meta :versionId] := #fhir/id"2"
          3 := nil))))

  (testing "doesn't return deleted resources"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Patient :id "0"}]
          [:put {:fhir/type :fhir/Observation :id "0"
                 :subject
                 {:fhir/type :fhir/Reference
                  :reference "Patient/0"}
                 :code
                 {:fhir/type :fhir/CodeableConcept
                  :coding
                  [{:fhir/type :fhir/Coding
                    :system #fhir/uri"system"
                    :code #fhir/code"code"}]}}]])
      @(d/transact
         node
         [[:delete "Observation" "0"]])
      (is (coll/empty? (d/compartment-query
                         (d/db node) "Patient" "0" "Observation"
                         [["code" "system|code"]])))))

  (testing "finds resources after deleted ones"
    (let [observation
          (fn [id code]
            {:fhir/type :fhir/Observation :id id
             :subject
             {:fhir/type :fhir/Reference
              :reference "Patient/0"}
             :code
             {:fhir/type :fhir/CodeableConcept
              :coding
              [{:fhir/type :fhir/Coding
                :system #fhir/uri"system"
                :code code}]}})]
      (with-open [node (new-node)]
        @(d/transact
           node
           [[:put {:fhir/type :fhir/Patient :id "0"}]
            [:put (observation "0" #fhir/code"code")]
            [:put (observation "1" #fhir/code"code")]])
        @(d/transact
           node
           [[:delete "Observation" "0"]])

        (given @(d/pull-many node
                             (d/compartment-query
                               (d/db node) "Patient" "0" "Observation"
                               [["code" "system|code"]]))
          [0 :fhir/type] := :fhir/Observation
          [0 :id] := "1"
          1 := nil))))

  (testing "returns the Observation in the Patient/0 compartment on the second criteria value"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Patient :id "0"}]
          [:put {:fhir/type :fhir/Observation :id "0"
                 :subject
                 {:fhir/type :fhir/Reference
                  :reference "Patient/0"}
                 :code
                 {:fhir/type :fhir/CodeableConcept
                  :coding
                  [{:fhir/type :fhir/Coding
                    :system #fhir/uri"system-191514"
                    :code #fhir/code"code-191518"}]}}]])

      (given @(d/pull-many node
                           (d/compartment-query
                             (d/db node) "Patient" "0" "Observation"
                             [["code" "foo|bar" "system-191514|code-191518"]]))
        [0 :fhir/type] := :fhir/Observation
        [0 :id] := "0"
        1 := nil)))

  (testing "with one patient and one observation"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Patient :id "0"}]
          [:put {:fhir/type :fhir/Observation :id "0"
                 :subject
                 {:fhir/type :fhir/Reference
                  :reference "Patient/0"}
                 :code
                 {:fhir/type :fhir/CodeableConcept
                  :coding
                  [{:fhir/type :fhir/Coding
                    :system #fhir/uri"system-191514"
                    :code #fhir/code"code-191518"}]}
                 :value
                 {:fhir/type :fhir/Quantity
                  :code #fhir/code"kg/m2"
                  :unit "kg/m²"
                  :system #fhir/uri"http://unitsofmeasure.org"
                  :value 42M}}]])

      (testing "matches second criteria"
        (given @(d/pull-many node
                             (d/compartment-query
                               (d/db node) "Patient" "0" "Observation"
                               [["code" "system-191514|code-191518"]
                                ["value-quantity" "42"]]))
          [0 :fhir/type] := :fhir/Observation
          [0 :id] := "0"
          1 := nil))

      (testing "returns nothing because of non-matching second criteria"
        (is (coll/empty?
              (d/compartment-query
                (d/db node) "Patient" "0" "Observation"
                [["code" "system-191514|code-191518"]
                 ["value-quantity" "23"]]))))))

  (testing "returns an anomaly on unknown search param code"
    (with-open [node (new-node)]
      (given (d/compartment-query (d/db node) "Patient" "0" "Observation"
                                  [["unknown" "foo"]])
        ::anom/category := ::anom/not-found)))

  (testing "Unknown compartment is not a problem"
    (with-open [node (new-node)]
      (is (coll/empty? (d/compartment-query (d/db node) "foo" "bar" "Condition" [["code" "baz"]])))))

  (testing "Unknown type is not a problem"
    (with-open [node (new-node)]
      @(d/transact
         node
         [[:put {:fhir/type :fhir/Patient
                 :id "id-0"}]])

      (given (d/compartment-query (d/db node) "Patient" "id-0" "Foo" [["code" "baz"]])
        ::anom/category := ::anom/not-found
        ::anom/message := "search-param with code `code` and type `Foo` not found")))

  (testing "Patient Compartment"
    (testing "Condition"
      (with-open [node (new-node)]
        @(d/transact
           node
           [[:put {:fhir/type :fhir/Patient
                   :id "id-0"}]
            [:put {:fhir/type :fhir/Condition
                   :id "id-1"
                   :code
                   {:fhir/type :fhir/CodeableConcept
                    :coding
                    [{:fhir/type :fhir/Coding
                      :system #fhir/uri"system-a-122701"
                      :code #fhir/code"code-a-122652"}]}
                   :subject
                   {:fhir/type :fhir/Reference
                    :reference "Patient/id-0"}}]
            [:put {:fhir/type :fhir/Condition
                   :id "id-2"
                   :code
                   {:fhir/type :fhir/CodeableConcept
                    :coding
                    [{:fhir/type :fhir/Coding
                      :system #fhir/uri"system-b-122747"
                      :code #fhir/code"code-b-122750"}]}
                   :subject
                   {:fhir/type :fhir/Reference
                    :reference "Patient/id-0"}}]])

        (testing "code"
          (given (into [] (d/compartment-query (d/db node) "Patient" "id-0" "Condition" [["code" "system-a-122701|code-a-122652"]]))
            [0 :id] := "id-1"
            1 := nil))))))



;; ---- Instance-Level History Functions --------------------------------------

(deftest instance-history
  (testing "a new node has an empty instance history"
    (with-open [node (new-node)]
      (is (coll/empty? (d/instance-history (d/db node) "Patient" "0")))
      (is (zero? (d/total-num-of-instance-changes (d/db node) "Patient" "0")))))

  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

      (testing "has one history entry"
        (is (= 1 (count (d/instance-history (d/db node) "Patient" "0"))))
        (is (= 1 (d/total-num-of-instance-changes (d/db node) "Patient" "0"))))

      (testing "contains that patient"
        (given @(d/pull-many node (d/instance-history (d/db node) "Patient" "0"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"))

      (testing "has an empty history on another patient"
        (is (coll/empty? (d/instance-history (d/db node) "Patient" "1")))
        (is (zero? (d/total-num-of-instance-changes (d/db node) "Patient" "1"))))))

  (testing "a node with one deleted patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:delete "Patient" "0"]])

      (testing "has two history entries"
        (is (= 2 (count (d/instance-history (d/db node) "Patient" "0"))))
        (is (= 2 (d/total-num-of-instance-changes (d/db node) "Patient" "0"))))

      (testing "the first history entry is the patient marked as deleted"
        (given @(d/pull-many node (d/instance-history (d/db node) "Patient" "0"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"2"
          [0 meta :blaze.db/op] := :delete))

      (testing "the second history entry is the patient marked as created"
        (given @(d/pull-many node (d/instance-history (d/db node) "Patient" "0"))
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "0"
          [1 :meta :versionId] := #fhir/id"1"
          [1 meta :blaze.db/op] := :put))))

  (testing "a node with two versions"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active false}]])

      (testing "has two history entries"
        (is (= 2 (count (d/instance-history (d/db node) "Patient" "0"))))
        (is (= 2 (d/total-num-of-instance-changes (d/db node) "Patient" "0"))))

      (testing "contains both versions in reverse transaction order"
        (given @(d/pull-many node (d/instance-history (d/db node) "Patient" "0"))
          [0 :active] := false
          [1 :active] := true))

      (testing "it is possible to start with the older transaction"
        (given @(d/pull-many node (d/instance-history (d/db node) "Patient" "0" 1))
          [0 :active] := true))

      (testing "overshooting the start-t returns an empty collection"
        (is (coll/empty? (d/instance-history (d/db node) "Patient" "0" 0))))))

  (testing "the database is immutable"
    (testing "while updating a patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active false}]])

        (let [db (d/db node)]
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

          (testing "the original database"
            (testing "has still only one history entry"
              (is (= 1 (count (d/instance-history db "Patient" "0"))))
              (is (= 1 (d/total-num-of-instance-changes db "Patient" "0"))))

            (testing "contains still the original patient"
              (given @(d/pull-many node (d/instance-history db "Patient" "0"))
                [0 :fhir/type] := :fhir/Patient
                [0 :id] := "0"
                [0 :active] := false
                [0 :meta :versionId] := #fhir/id"1"))))))))



;; ---- Type-Level History Functions ------------------------------------------

(deftest type-history
  (testing "a new node has an empty type history"
    (with-open [node (new-node)]
      (is (coll/empty? (d/type-history (d/db node) "Patient")))
      (is (zero? (d/total-num-of-type-changes (d/db node) "Patient")))))

  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

      (testing "has one history entry"
        (is (= 1 (count (d/type-history (d/db node) "Patient"))))
        (is (= 1 (d/total-num-of-type-changes (d/db node) "Patient"))))

      (testing "contains that patient"
        (given @(d/pull-many node (d/type-history (d/db node) "Patient"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"))

      (testing "has an empty observation history"
        (is (coll/empty? (d/type-history (d/db node) "Observation")))
        (is (zero? (d/total-num-of-type-changes (d/db node) "Observation"))))))

  (testing "a node with one deleted patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:delete "Patient" "0"]])

      (testing "has two history entries"
        (is (= 2 (count (d/type-history (d/db node) "Patient"))))
        (is (= 2 (d/total-num-of-type-changes (d/db node) "Patient"))))

      (testing "the first history entry is the patient marked as deleted"
        (given @(d/pull-many node (d/type-history (d/db node) "Patient"))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"2"
          [0 meta :blaze.db/op] := :delete))

      (testing "the second history entry is the patient marked as created"
        (given @(d/pull-many node (d/type-history (d/db node) "Patient"))
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "0"
          [1 :meta :versionId] := #fhir/id"1"
          [1 meta :blaze.db/op] := :put))))

  (testing "a node with two patients in two transactions"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "1"}]])

      (testing "has two history entries"
        (is (= 2 (count (d/type-history (d/db node) "Patient"))))
        (is (= 2 (d/total-num-of-type-changes (d/db node) "Patient"))))

      (testing "contains both patients in reverse transaction order"
        (given (into [] (d/type-history (d/db node) "Patient"))
          [0 :id] := "1"
          [1 :id] := "0"))

      (testing "it is possible to start with the older transaction"
        (given (into [] (d/type-history (d/db node) "Patient" 1))
          [0 :id] := "0"))

      (testing "overshooting the start-t returns an empty collection"
        (is (coll/empty? (d/type-history (d/db node) "Patient" 0))))))

  (testing "a node with two patients in one transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]
                         [:put {:fhir/type :fhir/Patient :id "1"}]])

      (testing "has two history entries"
        (is (= 2 (count (d/type-history (d/db node) "Patient"))))
        (is (= 2 (d/total-num-of-type-changes (d/db node) "Patient"))))

      (testing "contains both patients in the order of their ids"
        (given @(d/pull-many node (d/type-history (d/db node) "Patient"))
          [0 :id] := "0"
          [1 :id] := "1"))

      (testing "it is possible to start with the second patient"
        (given @(d/pull-many node (d/type-history (d/db node) "Patient" 1 "1"))
          [0 :id] := "1"))))

  (testing "the database is immutable"
    (testing "while updating a patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active false}]])

        (let [db (d/db node)]
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

          (testing "the original database"
            (testing "has still only one history entry"
              (is (= 1 (count (d/type-history db "Patient"))))
              (is (= 1 (d/total-num-of-type-changes db "Patient"))))

            (testing "contains still the original patient"
              (given @(d/pull-many node (d/type-history db "Patient"))
                [0 :fhir/type] := :fhir/Patient
                [0 :id] := "0"
                [0 :active] := false
                [0 :meta :versionId] := #fhir/id"1"))))))

    (testing "while adding another patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

        (let [db (d/db node)]
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "1"}]])

          (testing "the original database"
            (testing "has still only one history entry"
              (is (= 1 (count (d/type-history db "Patient"))))
              (is (= 1 (d/total-num-of-type-changes db "Patient"))))

            (testing "contains still the first patient"
              (given @(d/pull-many node (d/type-history db "Patient"))
                [0 :fhir/type] := :fhir/Patient
                [0 :id] := "0"
                [0 :meta :versionId] := #fhir/id"1"))))))))



;; ---- System-Level History Functions ----------------------------------------

(deftest system-history
  (testing "a new node has an empty system history"
    (with-open [node (new-node)]
      (is (coll/empty? (d/system-history (d/db node))))
      (is (zero? (d/total-num-of-system-changes (d/db node))))))

  (testing "a node with one patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

      (testing "has one history entry"
        (is (= 1 (count (d/system-history (d/db node)))))
        (is (= 1 (d/total-num-of-system-changes (d/db node)))))

      (testing "contains that patient"
        (given @(d/pull-many node (d/system-history (d/db node)))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"1"))))

  (testing "a node with one deleted patient"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:delete "Patient" "0"]])

      (testing "has two history entries"
        (is (= 2 (count (d/system-history (d/db node)))))
        (is (= 2 (d/total-num-of-system-changes (d/db node)))))

      (testing "the first history entry is the patient marked as deleted"
        (given @(d/pull-many node (d/system-history (d/db node)))
          [0 :fhir/type] := :fhir/Patient
          [0 :id] := "0"
          [0 :meta :versionId] := #fhir/id"2"
          [0 meta :blaze.db/op] := :delete))

      (testing "the second history entry is the patient marked as created"
        (given @(d/pull-many node (d/system-history (d/db node)))
          [1 :fhir/type] := :fhir/Patient
          [1 :id] := "0"
          [1 :meta :versionId] := #fhir/id"1"
          [1 meta :blaze.db/op] := :put))))

  (testing "a node with one patient and one observation in two transactions"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])
      @(d/transact node [[:put {:fhir/type :fhir/Observation :id "0"}]])

      (testing "has two history entries"
        (is (= 2 (count (d/system-history (d/db node)))))
        (is (= 2 (d/total-num-of-system-changes (d/db node)))))

      (testing "contains both resources in reverse transaction order"
        (given @(d/pull-many node (d/system-history (d/db node)))
          [0 :fhir/type] := :fhir/Observation
          [1 :fhir/type] := :fhir/Patient))

      (testing "it is possible to start with the older transaction"
        (given @(d/pull-many node (d/system-history (d/db node) 1))
          [0 :fhir/type] := :fhir/Patient))))

  (testing "a node with one patient and one observation in one transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]
                         [:put {:fhir/type :fhir/Observation :id "0"}]])

      (testing "has two history entries"
        (is (= 2 (count (d/system-history (d/db node)))))
        (is (= 2 (d/total-num-of-system-changes (d/db node)))))

      (testing "contains both resources in the order of their type hashes"
        (given @(d/pull-many node (d/system-history (d/db node)))
          [0 :fhir/type] := :fhir/Observation
          [1 :fhir/type] := :fhir/Patient))

      (testing "it is possible to start with the patient"
        (given @(d/pull-many node (d/system-history (d/db node) 1 "Patient"))
          [0 :fhir/type] := :fhir/Patient))))

  (testing "a node with two patients in one transaction"
    (with-open [node (new-node)]
      @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]
                         [:put {:fhir/type :fhir/Patient :id "1"}]])

      (testing "has two history entries"
        (is (= 2 (count (d/system-history (d/db node)))))
        (is (= 2 (d/total-num-of-system-changes (d/db node)))))

      (testing "it is possible to start with the second patient"
        (given @(d/pull-many node (d/system-history (d/db node) 1 "Patient" "1"))
          [0 :id] := "1"))))

  (testing "the database is immutable"
    (testing "while updating a patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active false}]])

        (let [db (d/db node)]
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0" :active true}]])

          (testing "the original database"
            (testing "has still only one history entry"
              (is (= 1 (count (d/system-history db))))
              (is (= 1 (d/total-num-of-system-changes db))))

            (testing "contains still the original patient"
              (given @(d/pull-many node (d/system-history db))
                [0 :fhir/type] := :fhir/Patient
                [0 :id] := "0"
                [0 :active] := false
                [0 :meta :versionId] := #fhir/id"1"))))))

    (testing "while adding another patient"
      (with-open [node (new-node)]
        @(d/transact node [[:put {:fhir/type :fhir/Patient :id "0"}]])

        (let [db (d/db node)]
          @(d/transact node [[:put {:fhir/type :fhir/Patient :id "1"}]])

          (testing "the original database"
            (testing "has still only one history entry"
              (is (= 1 (count (d/system-history db))))
              (is (= 1 (d/total-num-of-system-changes db))))

            (testing "contains still the first patient"
              (given @(d/pull-many node (d/system-history db))
                [0 :fhir/type] := :fhir/Patient
                [0 :id] := "0"
                [0 :meta :versionId] := #fhir/id"1"))))))))
