(ns arcadia.component.minigrid.episode-binder
  (:require [arcadia.utility.descriptors :as d]
   [arcadia.component.core :refer [Component]]))



;; maintains a representation that binds current contextual information into an episode.
;; The episode will contain:
;;   current minigrid map (whole map at this point, not divided by rooms)
;;   objects focused on since last encoding into LTM (even if they fall out of vSTM)
;;   events observed since last binding (these are stored longer than in event streams)
;; 
;;   spatial location of objects is different from their conceptual information?
;;     yes, so we need to map spatial locations in minigrid map to conceptual descriptions

(defn- describe-object
  "Produce a descriptor representation of an object."
  [obj]
  (apply d/descriptor
         (concat [:name "object" :world nil :type "instance"]
                 (reduce-kv #(if (string? %3) (conj %1 %2 %3) %1) [] (:arguments obj)))))

(defn- describe-event
  "Produce a descriptor representation of an event"
  [evt]
  (apply d/descriptor
         (concat [:name "event" :world nil :type "instance"]
                 (reduce-kv #(if (string? %3) (conj %1 %2 %3) %1) [] (:arguments evt)))))

;; [region descriptor] pair
;; support 1 to many mapping
;; {region [descriptors]}
(defn- object-mapping
  "Maps spatial regions to conceptual descriptions of the objects in those locations."
  [objs]
  ;; tuples: [region descriptor] pair for each object
  ;; result: {region (descriptors)} map to bind objects to locations
  (loop [tuples (map #(vector (-> % :arguments :region) (describe-object %)) objs)
         result {}]
    (if (empty? tuples)
      result
      (let [x (first tuples)]
        (recur (rest tuples) (update result (first x) conj (second x)))))))

(defn- episode-element [episode component]
  {:name "episode"
   :type "instance"
   :world nil
   :source component
   :arguments {;; we only care about matching the attended location and its container
               ;; this sort of assumes that places are unique. 
               :spatial {:location (d/descriptor :place (-> episode :allocentric :arguments :place)
                                                 :container (-> episode :allocentric :arguments :container))
                         :contents (object-mapping (:objects episode))}
               ;; we only care about the string fields of an object for now, but may extend to 
               ;; include numbers.
               :conceptual (map describe-object (:objects episode))
               ;; we only care about the string fields of an event for now, but may extend to include
               ;; keywords. 
               :temporal (map describe-event (:events episode))}})

(defrecord MinigridEpisodeBinder [buffer episode reset?]
  Component
  (receive-focus
   [component focus content]
    ;; store the episode as state and reset it when a boundary is detected, recall that boundaries
    ;; are currently signified by attended events. 
   (when (nil? @(:episode component))
     ;; establish events and objects as sets
     (reset! episode {:events #{} :objects #{}}))
   (let [episode  {:allocentric (d/first-element content :name "spatial-map" :perspective "allocentric")
                   :egocentric (d/first-element content :name "spatial-map" :perspective "egocentric")}]
     (if
      @(:reset? component)
       (do
         (swap! (:reset? component) not)
         (reset! (:episode component)
               ;; clear objects and events at episode boundary.
                 (merge episode {:events #{} :objects #{}})))
       (reset! (:episode component)
               (merge episode {;; collect all events that occur within an episode
                               :events (into (:events @(:episode component)) (d/filter-elements content :name "event" :type "instance" :world nil))
                               ;; collect objects that are the focus of attention during the episode
                               :objects (if (d/element-matches? focus :name "object" :world nil :type "instance")
                                          ;; drop some VSTM metadata to avoid issues with equality
                                          (conj (:objects @(:episode component)) (update-in focus [:arguments] dissoc :tracked? :slot))
                                          (:objects @(:episode component)))})))
     (when
      (d/element-matches? focus :name "event" :type "instance" :world nil)
       (swap! (:reset? component) not)))
   (reset! (:buffer component) (episode-element @(:episode component) component)))
  
;; (update-in {:a {:b {:x 3} :c 1}} [:a :b] dissoc :x)

  (deliver-result
    [component]
    #{@(:buffer component)}))

(defmethod print-method MinigridEpisodeBinder [comp ^java.io.Writer w]
  (.write w (format "MinigridEpisodeBinder{}")))

(defn start []
  (->MinigridEpisodeBinder (atom nil) (atom nil) (atom false)))