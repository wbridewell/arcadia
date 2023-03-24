(ns arcadia.component.minigrid.test-query
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

(defn- make-query []
  {:name "query"
   :arguments {:key "what"}
   :world "query"
   :type "instance"})

;; find an episode with a purple door
(defn- make-episode [component]
  {:name "episode"
   :arguments {:conceptual [(d/descriptor :name "object" :type "instance" :category "door" :color "purple")]}
   :world "query"
   :type "instance"})

(defn- make-task-cue []
  {:name "test"
   :arguments {:cue "recall"}
   :world nil
   :type "instance"}
  )

(defrecord MinigridTestQuery [buffer flag]
  Component
  (receive-focus
   [component focus content]
   (let [new-obj (-> (d/first-element content :name "visual-new-object") :arguments :object)]
     (reset! (:buffer component)
             (cond (d/element-matches? focus :name "subvocalize" :lexeme "what" :task :recall)
             ;; build an episode to serve as a query 
             ;; build a "what" query form 
                   [(make-episode component) (make-query)]

                   (and @flag (d/element-matches? new-obj :color "red" :category "door"))
                   ;; only allow one pass through this
                   (do (reset! flag false)
                       [(make-task-cue)])
                   
                   :else 
                   nil))))
  
  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method MinigridTestQuery [comp ^java.io.Writer w]
  (.write w (format "MinigridTestQuery{}")))

(defn start []
  (->MinigridTestQuery (atom nil) (atom true)))