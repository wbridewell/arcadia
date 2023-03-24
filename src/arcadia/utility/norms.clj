(ns ^{:doc "Utility functions for defining, processing, and interpreting norm constraints on relations."}
  arcadia.utility.norms
  (:require clojure.walk
            clojure.string
            [arcadia.utility [general :as g] [descriptors :as d] [relations :as rel]]))

(defn extract-args
  "Where var is either a relation or an object, and arg-map
  is a mapping from symbols to descriptors, returns a map
  of symbols to objects contained in var."
  [arg-map var-label var-binding]
  (if (symbol? var-label)
    {var-label var-binding}
    (zipmap (g/find-first #(and (sequential? %) (some (set (keys arg-map)) %)) var-label)
            (or (some->> var-binding :arguments :arguments)
                (some->> var-binding :arguments :objects)))))

(defn merge-objects
  "If both objects exist and are equal, this function returns one of them.
  Otherwise returns nil."
  [o1 o2 object-descriptors]
  (when (and o1 o2 (d/label-equals o1 o2 object-descriptors))
    o2))


(defmacro norm [name arg-map situation probability operator agent content object-descriptors]
  (let [vars# (repeatedly (count situation) #(gensym "R"))
        evaled-arg-map# (zipmap (map #(list 'quote %) (keys arg-map))
                               (vals arg-map))
        response-fn#
        `(fn [~@vars# content#]
           ;; bind all of the free variables to their instantiation
           (let [{:syms [~@(keys arg-map)] :as args#}
                 (apply merge-with #(merge-objects %1 %2 ~object-descriptors)
                        (map (partial extract-args ~evaled-arg-map#) '~situation (list ~@vars#)))

                 ;; insert object-descriptors into the relate call
                 rel# (try
                        ~(g/insert content 5
                                   `(mapv #(get-descriptor % ~object-descriptors)
                                          ~(nth content 4)))
                        (catch Exception ~(symbol "e")))]
             (when (and rel# (every? some? [~@(keys arg-map)]))
               (assoc (relate ~operator true
                              [~name ~@vars#] ;; the justification
                              [~agent rel#]     ;; arguments
                              [(get-descriptor ('~agent args#) ~object-descriptors)
                               {:label (to-string rel# true)}]  ;; argument descriptors/labels
                              "real")
                      :world "normal"))))]
    `{:name "norm"
      :arguments {:name ~name
                  :situation '~situation
                  :probability ~probability
                  :sr-link (sr-link ~(clojure.walk/postwalk-replace arg-map situation)
                                    ~response-fn#)
                  :operator ~operator
                  :arg-map ~evaled-arg-map#
                  :agent '~agent
                  :content '~content}
      :type "instance"
      :world nil}))

(defn resolved? [omission-event object-descriptors episodes content]
  (or (some #(let [o (-> omission-event :arguments :omission :arguments :arguments second)]
               (and (= (:name %) "relation")
                    (or (rel/relation-equals o %)
                        (and (-> o :arguments :arity (= 2))
                             (= (-> o :arguments :argument-descriptors second :label)
                                (-> % :arguments :argument-descriptors second :label))
                             (rel/relation-equals %
                               (assoc-in o [:arguments :argument-descriptors] (:argument-descriptors (:arguments %))))))))
            content)
      (some #(and (-> % :arguments :event-name
                      (clojure.string/starts-with? "compliance"))
                  (= (-> omission-event :arguments :norm :arguments :name)
                     (-> % :arguments :norm :arguments :name))
                  (d/label-equals (-> omission-event :arguments :objects first)
                                  (-> % :arguments :objects first)
                                  object-descriptors))
            (mapcat (comp :context :arguments) episodes))))
