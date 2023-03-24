(ns arcadia.component.scene-constructor
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

;;
;;
;; Focus Responsive
;;
;;
;; Default Behavior
;;
;;
;; Produces
;;
;;

(defrecord SceneConstructor [buffer] 
  Component
  (receive-focus
   [component focus content]
   ;; get any historical episode that was recalled
   (let [episode (d/first-element content :name "episode" :type "instance" :world "episodic-memory")]
     ;; request details from other long term memory systems
     )
   )
  
  (deliver-result
   [component]
   @buffer))

(defmethod print-method SceneConstructor [comp ^java.io.Writer w]
  (.write w (format "SceneConstructor{}")))

(defn start []
  (->SceneConstructor (atom nil)))