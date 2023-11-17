(ns blaze.db.impl.search-param.has
  "https://www.hl7.org/fhir/search.html#has"
  (:require
   [blaze.anomaly :as ba :refer [when-ok]]
   [blaze.async.comp :as ac]
   [blaze.coll.core :as coll]
   [blaze.db.impl.codec :as codec]
   [blaze.db.impl.index.resource-handle :as rh]
   [blaze.db.impl.index.resource-search-param-value :as r-sp-v]
   [blaze.db.impl.protocols :as p]
   [blaze.db.impl.search-param.special :as special]
   [blaze.db.impl.search-param.util :as u]
   [blaze.db.kv :as kv]
   [blaze.fhir.spec]
   [clojure.string :as str])
  (:import
   [com.github.benmanes.caffeine.cache Cache Caffeine]
   [java.util Comparator]
   [java.util.concurrent TimeUnit]
   [java.util.function Function]))

(set! *warn-on-reflection* true)

(defn- split-modifier [modifier]
  (if modifier
    (let [[type chain-code code] (str/split modifier #":")]
      (cond
        (str/blank? type)
        (ba/incorrect (format "Missing type in _has search param `_has:%s`." modifier))

        (str/blank? chain-code)
        (ba/incorrect (format "Missing chaining search param in _has search param `_has:%s`." modifier))

        (str/blank? code)
        (ba/incorrect (format "Missing search param in _has search param `_has:%s`." modifier))

        :else
        [type chain-code code]))
    (ba/incorrect "Missing modifier of _has search param.")))

(defn- search-param-not-found-msg [code type]
  (format "The search-param with code `%s` and type `%s` was not found."
          code type))

(defn- resolve-search-param [index type code]
  (if-let [search-param (get-in index [type code])]
    search-param
    (ba/not-found (search-param-not-found-msg code type))))

(defn- resolve-resource-handles!
  "Resolves a coll of resource handles of resources of type `tid`, referenced in
  the resource with `resource-handle` by `search-param`.

  Example:
   * `search-param`    - Encounter.subject
   * `tid`             - Patient
   * `resource-handle` - an Encounter
   * result            - a coll with one Patient

  Changes the state of `rsvi`. Consuming the collection requires exclusive
  access to `rsvi`. Doesn't close `iter`."
  {:arglists '([context rsvi search-param tid resource-handle])}
  [context rsvi {:keys [c-hash]} tid resource-handle]
  (coll/eduction
   (u/reference-resource-handle-mapper context)
   (let [tid-byte-string (codec/tid-byte-string tid)
         {:keys [tid id hash]} resource-handle]
     (r-sp-v/prefix-keys! rsvi tid (codec/id-byte-string id) hash c-hash
                          tid-byte-string))))

(def ^:private id-cmp
  (reify Comparator
    (compare [_ a b]
      (.compareTo ^String (rh/id a) (rh/id b)))))

(defn- drop-lesser-ids [start-id]
  (drop-while #(neg? (let [^String id (rh/id %)] (.compareTo id start-id)))))

(defn- resource-handles*
  [{:keys [snapshot] :as context} tid [search-param chain-search-param join-tid value]]
  (with-open [rsvi (kv/new-iterator snapshot :resource-value-index)]
    (into
     (sorted-set-by id-cmp)
     (mapcat #(resolve-resource-handles! context rsvi chain-search-param tid %))
     (p/-resource-handles search-param context join-tid nil value))))

;; TODO: make this cache public and configurable?
(def ^:private ^Cache resource-handles-cache
  (-> (Caffeine/newBuilder)
      (.maximumSize 100)
      (.expireAfterAccess 1 TimeUnit/MINUTES)
      (.build)))

(defn- resource-handles
  "Returns a sorted set of resource handles of resources of type `tid`,
  referenced from resources of the type `join-tid` by `chain-search-param` that
  have `value` according to `search-param`."
  {:arglists '([context tid [search-param chain-search-param join-tid value]])}
  [{:keys [t] :as context} tid
   [{:keys [c-hash]} {chain-c-hash :c-hash} join-tid value :as data]]
  (let [key [t tid join-tid chain-c-hash c-hash value]]
    (.get resource-handles-cache key
          (reify Function
            (apply [_ _]
              (resource-handles* context tid data))))))

(defn- matches?
  [context {:keys [tid] :as resource-handle} value]
  (contains? (resource-handles context tid value) resource-handle))

(defrecord SearchParamHas [index name type code]
  p/SearchParam
  (-compile-value [_ modifier value]
    (when-ok [[type chain-code code] (split-modifier modifier)
              search-param (resolve-search-param index type code)
              chain-search-param (resolve-search-param index type chain-code)]
      [search-param
       chain-search-param
       (codec/tid type)
       (p/-compile-value search-param nil value)]))

  (-resource-handles [_ context tid _ value]
    (resource-handles context tid value))

  (-resource-handles [_ context tid _ value start-id]
    (coll/eduction
     (drop-lesser-ids (codec/id-string start-id))
     (resource-handles context tid value)))

  (-count-resource-handles [search-param context tid modifier value]
    (ac/completed-future
     (count (p/-resource-handles search-param context tid modifier value))))

  (-matches? [_ context resource-handle _ values]
    (some? (some #(matches? context resource-handle %) values)))

  (-index-values [_ _ _]
    []))

(defmethod special/special-search-param "_has"
  [index _]
  (->SearchParamHas index "_has" "special" "_has"))
