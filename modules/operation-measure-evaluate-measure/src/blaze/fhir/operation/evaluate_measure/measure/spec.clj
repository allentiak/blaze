(ns blaze.fhir.operation.evaluate-measure.measure.spec
  (:require
   [blaze.db.spec]
   [blaze.elm.resource :as cr]
   [blaze.fhir.operation.evaluate-measure.measure :as-alias measure]
   [blaze.fhir.spec.spec]
   [clojure.spec.alpha :as s]))

(s/def ::measure/report-type
  #{"subject" "subject-list" "population"})

(s/def ::measure/subject-ref
  (s/or :id :blaze.resource/id
        :local-ref :blaze.fhir/literal-ref-tuple))

(s/def ::measure/population-handle
  cr/resource?)

(s/def ::measure/subject-handle
  cr/resource?)

(s/def ::measure/handle
  (s/keys :req-un [::measure/population-handle ::measure/subject-handle]))

(s/def ::measure/handles
  (s/coll-of ::measure/handle))
