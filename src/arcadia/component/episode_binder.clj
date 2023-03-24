(ns arcadia.component.episode-binder
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.utility.general :as g]
            [arcadia.utility.geometry :as geo]
            [arcadia.utility.objects :as obj]
            [arcadia.component.core :refer [Component]]))

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

;; maintains a representation that binds current contextual information into an episode.
;; The episode will contain:
;;   allocentric layout
;;   egocentric layout
;;   current set of events
;;   objects in working memory that appear in the egocentric layout

(defn- visible-objects
  "Return a sequence of objects in the order of the locations they match. Locations without a matching object
   will be nil to preserve sequence length."
  [locations objects]
  ;; the location of a spatial map entry will be a region
  ;; compare that to the regions of the object files
  (map (fn [x] (g/find-first #(geo/= (:location x) (-> % :arguments :region)) objects)) locations))


(defn- episode-element [episode]
  {:name "episode"
   :type "instance"
   :world nil
   :arguments {;; we only care about matching the attended location and its container
               ;; this sort of assumes that places are unique. 
               :spatial (d/descriptor :place (-> episode :allocentric :arguments :place)
                                      :container (-> episode :allocentric :arguments :container))
               ;; we only care about the string fields of an object for now, but may extend to 
               ;; include numbers.
               :conceptual (map (fn [x] (apply d/descriptor
                                               (concat [:name "object" :world nil :type "instance"]
                                                       (reduce-kv #(if (string? %3) (conj %1 %2 %3) %1) [] (:arguments x)))))
                                (-> episode :objects))
               ;; we only care about the string fields of an event for now, but may extend to include
               ;; keywords. 
               :temporal (map (fn [x] (apply d/descriptor
                                             (concat [:name "event" :world nil :type "instance"]
                                                     (reduce-kv #(if (string? %3) (conj %1 %2 %3) %1) [] (:arguments x)))))
                              (-> episode :events))}})

(defrecord EpisodeBinder [buffer episode]
  Component
  (receive-focus
    [component focus content]
    ;; (display/env "Episode Binder")
    (let [egocentric-map (d/first-element content :name "spatial-map" :perspective "egocentric")]
     ;; XXX store this representation internally. output the representation with descriptors
     ;; this will match against long-term episodic memory
      (reset! (:episode component)
              {:allocentric (d/first-element content :name "spatial-map" :perspective "allocentric")
               :egocentric egocentric-map
            ;; set of current events, need to determine when to store episodes in memory
               ;; NOTE: probably this should be "event-stream" instead
               :events (d/filter-elements content :name "event-episode" :world "episodic-memory")
            ;; tracked vstm elements that are at the locations in the spatial map
               :objects (visible-objects (-> egocentric-map :arguments :layout :objects)
                                         (obj/get-vstm-objects content :tracked? true))}))
    (reset! (:buffer component) (episode-element @(:episode component))))

  (deliver-result
    [component]
    (list @buffer)))

(defmethod print-method EpisodeBinder [comp ^java.io.Writer w]
  (.write w (format "EpisodeBinder{}")))

(defn start []
  (->EpisodeBinder (atom nil) (atom nil)))