(ns arcadia.component.relation.memorizer
  "Update working-memory representations of all valid relations.
  Real relations are only updated if they don't already exist
  in WM, while all other relations are updated whenever possible.
  This behavior prevents the need to constantly update real relations in WM,
  but allows for other relations to be overwritten with identical truth values.
  For instance, if running a tighter scan still yields uncertainty, the existing
  relations need to be updated to reflect that they came from a new scan.

  Focus Responsive:
     No.

  Default Behavior:
     Creates a memory-update request for all real relations not already
     present in working memory, and for all relations in hypothetical contexts.

  Produces
   * memory-update- a request to memorize the relation being perceived"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility [general :as g] [relations :as rel]]))


(defn- memory-update
  "Update the relation"
  [old new source]
  {:name "memory-update"
   :arguments {:old old :new new}
   :type "action"
   :source source
   :world nil})

(defn- update-relation
  "If relations is a set of relations with different truth values
  than the current values for the relation, update them in working memory."
  [relations component wm-relations]
  (let [old-rels (filter (fn [r] (some #(rel/relation-equals r % false) relations))
                         wm-relations)]
    (when (or (-> relations first :arguments :context (not= "real"))
              (not= (rel/truth-values old-rels) (rel/truth-values relations)))
      (memory-update old-rels relations component))))

(defrecord RelationMemorizer [buffer] Component
  (receive-focus
   [component focus content]
   (let [wm-relations (filter #(and (= (:world %) "working-memory")
                                    (= (:name %) "relation")) content)
         ;; relations waiting to be updated
         updating-relations (mapcat (comp :new :arguments)
                                    (filter (fn [m] (and (= (:name m) "memory-update")
                                                         (:new (:arguments m))
                                                         (some #(rel/valid-relation? %)
                                                               (:new (:arguments m)))))
                                            content))]
     (->> content
          (filter (fn [r] (and (rel/valid-relation? r)
                               (nil? (:world r))
                               (not-any? (partial rel/relation-equals r)
                                         updating-relations))))
          ;; ignore value, include context
          (group-by #(rel/to-string % false true))
          (map #(update-relation (g/distinctp rel/relation-equals (val %))
                                 component wm-relations))
          (remove nil?)
          (reset! (:buffer component)))))

  (deliver-result
   [component]
   (set @(:buffer component))))

(defmethod print-method RelationMemorizer [comp ^java.io.Writer w]
  (.write w (format "RelationMemorizer{}")))

(defn start []
  (->RelationMemorizer (atom nil)))
