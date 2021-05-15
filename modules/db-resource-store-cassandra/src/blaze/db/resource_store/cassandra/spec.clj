(ns blaze.db.resource-store.cassandra.spec
  (:require
    [clojure.spec.alpha :as s])
  (:import
    [com.datastax.oss.driver.api.core DefaultConsistencyLevel]
    [java.util EnumSet]))


(s/def :blaze.db.resource-store.cassandra/contact-points
  (s/and string? #(re-matches #"[^:]+:\d+(,[^:]+:\d+)*" %)))


(s/def :blaze.db.resource-store.cassandra/username
  string?)


(s/def :blaze.db.resource-store.cassandra/password
  string?)


(s/def :blaze.db.resource-store.cassandra/key-space
  string?)


(s/def :blaze.db.resource-store.cassandra/put-consistency-level
  (into #{} (map #(.name %)) (EnumSet/allOf DefaultConsistencyLevel)))


(s/def :blaze.db.resource-store.cassandra/max-concurrent-read-requests
  nat-int?)


(s/def :blaze.db.resource-store.cassandra/max-read-request-queue-size
  nat-int?)
