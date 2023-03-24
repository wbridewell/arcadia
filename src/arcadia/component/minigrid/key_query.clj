(ns arcadia.component.minigrid.key-query
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]
            [arcadia.component.core :refer [Component]]))

(defn- make-query []
  {:name "query"
   :arguments {:key "what"}
   :world "query"
   :type "instance"})

(defn- make-key-episode [component]
  {:name "episode"
   :arguments {:conceptual [(d/descriptor :name "object" :type "instance" :category "key" :color @(:color component))]}
   :world "query" 
   :type "instance"})

(defn- make-door-episode [component]
  {:name "episode"
   :arguments {:conceptual [(d/descriptor :name "object" :type "instance" :category "door" :state "locked")]}
   :world "query"
   :type "instance"})


(defn- make-task-cue []
  {:name "key"
   :arguments {:cue "recall"}
   :world nil
   :type "instance"})

(defrecord MinigridKeyQuery [buffer color]
  Component
  (receive-focus
   [component focus content] 
   (reset! (:buffer component)
           ;; if you're ready to recall the key location, try it. 
           (cond (d/element-matches? focus :name "subvocalize" :lexeme "what" :task :find-key)
                 [(make-key-episode component) (make-query)]

                 (d/element-matches? focus :name "subvocalize" :lexeme "what" :task :locked-door)
                 [(make-door-episode component) (make-query)]

                 ;; if you see a locked door and don't have the right key, switch to the task 
                 ;; that would let you try to remember where you saw that key.
                 (and (d/element-matches? focus :category "door" :state "locked")
                      (not (and (= "key" (:category (mg/inventory content)))
                                (= (-> focus :arguments :color) (:color (mg/inventory content))))))
                 (do (reset! color (-> focus :arguments :color))
                     [(make-task-cue)]))))
  
  (deliver-result
    [component]
    (into () @buffer)))

(defmethod print-method MinigridKeyQuery [comp ^java.io.Writer w]
  (.write w (format "MinigridKeyQuery{}")))

(defn start []
  (->MinigridKeyQuery (atom nil) (atom nil)))