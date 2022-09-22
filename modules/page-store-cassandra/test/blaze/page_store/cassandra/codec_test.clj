(ns blaze.page-store.cassandra.codec-test
  (:require
    [blaze.page-store.cassandra.codec :as codec]
    [blaze.spec]
    [blaze.test-util :refer [satisfies-prop]]
    [clojure.spec.alpha :as s]
    [clojure.spec.test.alpha :as st]
    [clojure.test :as test :refer [deftest]]
    [clojure.test.check.properties :as prop]
    [cuerdas.core :as c-str]))


(st/instrument)


(defn- fixture [f]
  (st/instrument)
  (f)
  (st/unstrument))


(test/use-fixtures :each fixture)


(def token (c-str/repeat "A" 32))


(deftest encode-decode-test
  (satisfies-prop 100
    (prop/for-all [clauses (s/gen :blaze.db.query/clauses)]
      (= clauses (codec/decode (codec/encode clauses) token)))))