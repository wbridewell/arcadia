(ns arcadia.component.relation.detector
  "TODO: add documentation"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]))

;; This component encodes real, true instances of ongoing relations as
;; course-grain events dependent on the fine-grained events constituting the relation

(def ^:private relation-descriptor
  (d/descriptor :name "relation" :type "instance" :world nil :context "real"))

(defn relation-event [relation]
  {:name "event"
   :arguments {:event-name (-> relation :arguments :predicate-name)
               ;:event-lifespan nil
               :objects (-> relation :arguments :arguments)
               :relation relation}
   :type "instance"
   :world nil})

(defrecord RelationDetector [buffer]
  Component
  (receive-focus
    [component focus content]
    (->> content
         (d/filter-matches relation-descriptor)
         (map #(relation-event %))
         (reset! (:buffer component))))
  (deliver-result
    [component]
    @buffer))

(defmethod print-method RelationDetector [comp ^java.io.Writer w]
  (.write w (format "RelationDetector{}")))

(defn start []
  (->RelationDetector (atom nil)))
