(ns arcadia.component.minigrid.episode-binder
  "Maintains a representation that binds current contextual information into an episode.
   
  Focus Responsive
     Yes. 
     A new episode starts when a boundary is the focus. Information is recorded about objects 
     that are in focus.
  Default Behavior
    Outputs the current episode. Collects all events that occur and all objects that receive
    focus. Also collects spatial-map information. 
  Produces
    episode
      :type - instance
      :world - nil (this is not episodic memory, per se)
      :spatial - map with :location and :contents
        :location - a descriptor with the :place and :container fields from the allocentric map
        :contents - a map of regions to sequences of objects at that location
      :conceptual - a sequence of object descriptors
      :temporal - a sequence of event descriptors"
  (:require [arcadia.utility.descriptors :as d]
   [arcadia.component.core :refer [Component merge-parameters]]))

(def ^:parameter ^:required episode-boundaries 
  "sequence of descriptors that signal when a new episode has begun" nil)

(defn- describe-object
  "Produce a descriptor representation of an object."
  [obj]
  (apply d/descriptor
         (concat [:name "object" :world nil :type "instance"]
                 (reduce-kv #(if (string? %3) (conj %1 %2 %3) %1) [] (:arguments obj)))))


;; NOTE:
;; it is unclear what information from events we should keep. because we need to know location
;; information for "location reached" events, we will keep maps. maybe we keep more than this.
;; it's hard to say. we would like information to be distributed, but we do need events to 
;; contain enough information that we can reconstruct scenarios, and for location-reached, the
;; location seems like the minimal amount of information necessary.
(defn- describe-event
  "Produce a descriptor representation of an event"
  [evt]
  (apply d/descriptor
         (concat [:name "event" :world nil :type "instance"]
                 (reduce-kv #(if (or (string? %3) (map? %3)) (conj %1 %2 %3) %1) [] (:arguments evt)))))

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

(defn- episode-element [episode]
  {:name "episode"
   :type "instance"
   :world nil
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

(defrecord MinigridEpisodeBinder [buffer episode boundaries reset?]
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
      (when @(:reset? component)
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
     ;; NOTE: new episode on boundary events, not on every event.   
     ;; should mirror encoding pattern in episodic-ltm. 
    (when (some #(d/descriptor-matches? % focus) (:boundaries component))
      (swap! (:reset? component) not))
    (reset! (:buffer component) (episode-element @(:episode component))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method MinigridEpisodeBinder [comp ^java.io.Writer w]
  (.write w (format "MinigridEpisodeBinder{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->MinigridEpisodeBinder (atom nil) (atom nil) (:episode-boundaries p) (atom false))))