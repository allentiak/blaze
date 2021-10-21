(ns blaze.jepsen.register
  (:refer-clojure :exclude [read])
  (:require
    [blaze.anomaly :as ba]
    [blaze.async.comp :as ac]
    [blaze.fhir-client :as fhir-client]
    [blaze.fhir.spec.type :as type]
    [blaze.jepsen.util :as u]
    [clojure.tools.logging :refer [info]]
    [hato.client :as hc]
    [jepsen.checker :as checker]
    [jepsen.cli :as cli]
    [jepsen.client :as client]
    [jepsen.generator :as gen]
    [jepsen.nemesis :as nemesis]
    [jepsen.tests :as tests]
    [knossos.model :as model])
  (:import
    [java.util UUID]))


(defn r [_ _]
  {:type :invoke :f :read :value nil})


(defn w [_ _]
  {:type :invoke :f :write :value (int (rand-int 100))})


(defn read [{:keys [base-uri] :as context} id]
  @(-> (fhir-client/read base-uri "Patient" id context)
       (ac/then-apply :multipleBirth)
       (ac/exceptionally #(when-not (ba/not-found? %) %))))


(defn write! [{:keys [base-uri] :as context} id value]
  @(fhir-client/update
     base-uri
     {:fhir/type :fhir/Patient :id id :multipleBirth value}
     context))


(defn failing-write! [{:keys [base-uri] :as context}]
  @(-> (fhir-client/update
         base-uri
         {:fhir/type :fhir/Observation :id "0"
          :subject (type/map->Reference {:reference (str "Patient/" (UUID/randomUUID))})}
         context)
       (ac/exceptionally (constantly nil))))


(defrecord Client [context]
  client/Client
  (open! [this _test node]
    (info "Open client on node" node)
    (update this :context assoc
            :base-uri (str "http://" node "/fhir")
            :http-client (hc/build-http-client {:connect-timeout 10000})))

  (setup! [this _test]
    this)

  (invoke! [_ test op]
    (case (:f op)
      :read (assoc op :type :ok :value (read context (:id test)))
      :write (do (write! context (:id test) (:value op))
                 (assoc op :type :ok))))

  (teardown! [this _test]
    this)

  (close! [_ _test]))


(defn trash-sender
  "Sends trash requests."
  [node]
  (let [context {:base-uri (str "http://" node "/fhir")}]
    (reify nemesis/Nemesis
      (setup! [this _] this)

      (invoke! [_ _ op]
        (case (:f op)
          :failing-write (do (failing-write! context)
                             op)))

      (teardown! [_ _]))))


(defn blaze-test
  "Given an options map from the command line runner (e.g. :nodes, :ssh,
  :concurrency, ...), constructs a test map."
  [opts]
  (merge
    tests/noop-test
    {:pure-generators true
     :name "register"
     :remote (u/->Remote)
     :client (->Client {})
     :nemesis (trash-sender (first (:nodes opts)))
     :checker (checker/linearizable
                {:model (model/register)
                 :algorithm :linear})
     :generator (->> (gen/mix [r w])
                     (gen/stagger (:delta-time opts))
                     (gen/nemesis
                       (cycle
                         [{:type :info, :f :failing-write}
                          (gen/sleep 1)]))
                     (gen/time-limit (:time-limit opts)))}
    opts))


(def cli-opts
  "Additional command line options."
  [[nil "--id ID" "The ID of the patient to use." :default (str (UUID/randomUUID))]
   [nil "--delta-time s" "The duration between requests."
    :default 0.1
    :parse-fn #(Double/parseDouble %)]])


(defn -main
  "Handles command line arguments. Can either run a test, or a web server for
  browsing results."
  [& args]
  (cli/run! (cli/single-test-cmd {:test-fn blaze-test :opt-spec cli-opts})
            args))