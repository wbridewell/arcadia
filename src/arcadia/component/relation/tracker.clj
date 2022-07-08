(ns arcadia.component.relation.tracker
  "The RelationTracker component searches for requests to instantiate relations,
  and carries through instantiation requests when a valid relation is produced
  that is not already being stored in working-memory

  Focus Responsive
    No.

  Default Behavior
    Broadcast instances of all relations which hold over the current
    content of episodic-memory.

  Produces
    relation- an instance of a relation which currently holds, given the
              constraints outlined in the relation's definition"
  (:require [arcadia.component.core :refer [Component merge-parameters]]
            (arcadia.utility [relations :as rel])))

(def ^:parameter ^:required object-descriptors "a sequence of descriptors (required)" nil)

(defn- instantiate-relation [component request]
  (rel/relate request (:object-descriptors component) component))

(defrecord RelationTracker [buffer object-descriptors] Component
  (receive-focus
   [component focus content]
   (reset! (:buffer component) nil)

   ;; generate instances of each satisfied relation and
   ;; broadcast all valid relation instances to accessible content
   (doseq [rel (mapcat (partial instantiate-relation component)
                       (filter #(= (:name %) "instantiate") content))
           :when rel]
     (swap! (:buffer component) conj rel)))

  (deliver-result
   [component]
   @(:buffer component)))

(defmethod print-method RelationTracker [comp ^java.io.Writer w]
  (.write w (format "RelationTracker{}")))

(defn start
  "Start the RelationTracker component. object-descriptors is a list
   of descriptors used in labeling objects."
  [& {:as args}]
  (->RelationTracker (atom []) (:object-descriptors (merge-parameters args))))
