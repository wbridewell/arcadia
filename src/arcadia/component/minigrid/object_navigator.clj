(ns arcadia.component.minigrid.object-navigator
  "TODO"
  (:require [arcadia.component.core :refer [Component]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.minigrid :as mg]))

(defn- make-navpoint [obj source]
  (when obj
    {:name "minigrid-navigation"
     :arguments {:goal obj}
     :world nil
     :source source
     :type "action"}))

(defn- report-goal [goal component]
  {:name "goal-reached"
   :arguments {:goal goal}
   :world nil
   :source component
   :type "instance"})

(defn- navpoint-reached? [obj pos]
  (let [obj-pos (-> obj :arguments :region)]
    (and (= (:x obj-pos) (first pos)) (= (:y obj-pos) (second pos)))))

(defn- need-key? [obj inventory]
  (and (= (-> obj :arguments :category) "door")
       (= (-> obj :arguments :state) "locked")
       (or (not= (-> inventory :category) "key")
           (not= (-> inventory :color) (-> obj :arguments :color)))))

(defrecord MinigridObjectNavigator [buffer]
  Component
  (receive-focus
   [component focus content]
   (reset! (:buffer component) nil)
   (let [prev-nav (d/first-element content :type "action" :name "minigrid-navigation")
         egocentric (d/first-element content :name "spatial-map" :perspective "egocentric")]
     (if (and prev-nav egocentric)
       (if (navpoint-reached? (-> prev-nav :arguments :goal) (-> egocentric :arguments :location))
         (reset! (:buffer component) (report-goal (-> prev-nav :arguments :goal) component))
         (reset! (:buffer component) prev-nav))
       (when (and (d/element-matches? focus :type "instance" :name "object" :category #(not (#{"key"} %)) :world nil)
                  (not (need-key? focus (mg/inventory content))))
         (reset! (:buffer component) (make-navpoint focus component))))))

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method MinigridObjectNavigator [comp ^java.io.Writer w]
  (.write w (format "MinigridObjectNavigator{}")))

(defn start []
  (->MinigridObjectNavigator (atom nil)))
