(ns arcadia.component.affordance-tracker 
  "Focus Responsive
    Yes. When a navigation-affordance is the focus, it is removed from the tracker.
  Default Behavior
    When there is an adopt-navigation-target element, this component turns that into 
    a navigation affordance that can be adopted in the future. The affordances are 
    presented in order until they receive focus or until the location they specify 
    has been visited.
  Produces
    (the arguments are taken from adopt-navigation-goal elements. see the minigrid
     schemas to find the current representation)
    * navigation-affordance 
        :goal - the object being navigated to
        :location - the location of the object (a region)
        :reason - the source for the affordance
        :description - the descriptor that matched the target object"  
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

(defn- navigation-affordance [g]
  {:name "navigation-affordance"
   :arguments (:arguments g)
   :type "instance"
   :world nil})

;; initially only works for navigation, but maybe make trackable elements a parameter
(defrecord AffordanceTracker [buffer memory parameters]
  Component
  (receive-focus
   [component focus content]
   ;; turn goals into affordances
   (reset! memory (concat @memory
                          (map #(navigation-affordance %)
                               (d/filter-elements content :name "adopt-navigation-target" :type "action"))))

   ;; remove affordances that have been considered at least or that have been nullified because
   ;; the location was already visited.
   ;; clear the buffer
   (when (d/element-matches? focus :name "navigation-affordance" :type "instance" :world nil)
     (swap! memory (partial remove #{focus}))
     (reset! buffer nil))
   (let [loc (get-in (d/first-element content :name "spatial-map" :world nil) [:arguments :location])
         [old new-mem] ((juxt filter remove) #(= loc (mapv (-> % :arguments :location) [:x :y])) @memory)]
     (when (seq old)
       (reset! memory new-mem)
       (reset! buffer nil)))

   (when (empty? @buffer)
     (reset! buffer (first @memory))))
  
  (deliver-result
   [component]
   (list @buffer)))

(defmethod print-method AffordanceTracker [comp ^java.io.Writer w]
  (.write w (format "AffordanceTracker{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->AffordanceTracker (atom nil) (atom nil) p)))