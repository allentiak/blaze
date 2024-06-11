(ns blaze.fhir.operation.evaluate-measure.measure.util
  (:require
   [blaze.anomaly :as ba]
   [blaze.fhir.spec.type :as type]
   [blaze.luid :as luid]))

(defn expression-name [population-path-fn criteria]
  (let [language (-> criteria :language type/value)
        expression (-> criteria :expression type/value)]
    (cond
      (nil? criteria)
      (ba/incorrect
       "Missing criteria."
       :fhir/issue "required"
       :fhir.issue/expression (population-path-fn))

      (not (#{"text/cql" "text/cql-identifier"} language))
      (ba/unsupported
       (format "Unsupported language `%s`." language)
       :fhir/issue "not-supported"
       :fhir.issue/expression (str (population-path-fn) ".criteria.language"))

      (nil? expression)
      (ba/incorrect
       "Missing expression."
       :fhir/issue "required"
       :fhir.issue/expression (str (population-path-fn) ".criteria"))

      :else
      expression)))

(defn list-reference [list-id]
  (type/map->Reference {:reference (str "List/" list-id)}))

(defn- resource-reference [{:keys [id] :as resource}]
  (type/map->Reference {:reference (str (name (type/type resource)) "/" id)}))

(defn population-tx-ops [list-id handles]
  [[:create
    {:fhir/type :fhir/List
     :id list-id
     :status #fhir/code"current"
     :mode #fhir/code"working"
     :entry
     (mapv
      (fn [{:keys [population-handle]}]
        {:fhir/type :fhir.List/entry
         :item (resource-reference population-handle)})
      handles)}]])

(defn- merge-result*
  "Merges `result` into the return value of the reduction `ret`."
  {:arglists '([ret result])}
  [ret {:keys [result tx-ops] ::luid/keys [generator]}]
  (cond-> (update ret :result conj result)
    generator
    (assoc ::luid/generator generator)
    (seq tx-ops)
    (update :tx-ops into tx-ops)))

(defn merge-result
  "Merges `result` into the return value of the reduction `ret`.

  Returns a reduced value if `result` is an anomaly."
  [ret result]
  (-> result
      (ba/map (partial merge-result* ret))
      (ba/exceptionally reduced)))
