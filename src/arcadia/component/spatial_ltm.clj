(ns arcadia.component.spatial-ltm
  "This component stores visual representations for the purposes of enabling
   recognition and recollection. The stored information supports the production 
   of familiarity signals and scene reconstruction.

  Focus Responsive
    * memory-retrieval

  Produces an element for ...

  Default Behavior
  None

  Produces
   ..."
  (:require [arcadia.utility.descriptors :as d]
            [arcadia.component.core :refer [Component merge-parameters]]))

;; NOTE:
;; initially developed for 2D displays where each presented image is of the 
;; same size / resolution and there is no movement of the camera in space. 
;; for other forms of environments, this will not suffice and an allocentric
;; cognitive map will need to be created and stored.
;;
;; long term spatial memory appears to be allocentric with egocentric, short-term
;; representations being converted to allocentric representations and then 
;; when recalled, the egocentric representations will be reconstructed from 
;; the long-term stores. 
;;
;; this should be allocentric and not involved spatial relations between 
;; objects. because we are working with a 2D, flat, visual representation, 
;; we can use the coordinate plane as a reference axis. 
;;
;; we don't need to be overly concerned with psychological studies about the 
;; reliability of spatial memory at this point. rectifying all the results 
;; into a meta-analysis would take considerable time and effort if it is at
;; all possible (recent evidence calls some methodologies into question).
;; we can be 100% pixel-reliable or use a reduced-resolution grid representation
;; if we want to factor out scale. 

;; Some recent papers to read before complaining.
;;
;; Schultheis, H. (2021). Alignment in spatial memory: Encoding of reference 
;; frames or of relations? Psychonomic Bulletin and Review, 28(1), 249–258. 
;; https://doi.org/10.3758/s13423-020-01791-y
;;
;; Heywood-Everett, E., Baker, D. H., & Hartley, T. (2022). Testing the 
;; precision of spatial memory representations using a change-detection 
;; task: effects of viewpoint change. Journal of Cognitive Psychology, 34(1), 
;; 127–141. https://doi.org/10.1080/20445911.2020.1863414
;;
;; Aagten-Murphy, D., & Bays, P. M. (2019). Independent working memory 
;; resources for egocentric and allocentric spatial information. PLoS 
;; Computational Biology. https://doi.org/10.1371/journal.pcbi.1006563

;; We will use a hierarchical organization of space, so that the lab display
;; sits within a lab room. For more complete maps, we will included multiple
;; levels of organization, such as a particular building, neighborhood, city.
;; What defines the boundaries of a level of organization is not clear, but
;; we can use artificial boundaries (rooms, buildings, etc.) as a guide.

;; when a spatial memory comes in, you know where your current location is, 
;; (I am in the lab room) and you position the object or sublevel of 
;; organization there. we will not handle partitioning of existing levels, yet. 
;; that is, if you added a wall to divide a room in half (even if temporary), the 
;; relationship between post-wall and pre-wall memories could be complicated.
;; 
;; another option for dynamism is the use of cubicles in an open work space. 
;; moving the cubicles amounts to moving objects around the room, but because
;; cubicles are sub-levels, there may be a realignment process if cubicle 
;; contents are shifted with cubicles. 

(defn- spatial-data [x]
  (select-keys x [:place :container :layout]))

(defn- recall-element
  [x component]
  (when x
    (assoc x :source component :world "memory")))

(defrecord SpatialLTM [buffer database]
  Component

  (receive-focus
   [component focus content]
   (let [egocentric-map (d/first-element content :name "spatial-map" :perspective "egocentric")
         allocentric-map (d/first-element content :name "spatial-map" :perspective "allocentric")]
     (reset! (:buffer component) nil)
     (cond
       (d/element-matches? focus :name "memory-retrieval" :type "action" :world nil)
       (when-let [query (-> focus :arguments :descriptor)]
         (reset! (:buffer component)
                 (recall-element
                  (d/rand-match query (seq @(:database component)))
                  component)))

       ;; store a layout in spatial LTM when an object is explicitly stored in working memory.
       ;; the layout for the same location will be updated with the latest version, which is 
       ;; not quite right because we can obviously recognize major differences in layout from 
       ;; previous visits. what we lack is a meaningful change detector for layouts that could
       ;; treat maps of the same location as different from each other, say as "B1-v1" and "B1-v2"
       (d/element-matches? focus :name "memorize" :type "action" :world nil
                           :element #(d/element-matches? % :name "object"))
       (swap! (:database component) conj (spatial-data (:arguments allocentric-map))))))

  (deliver-result
    [component]
    (set @(:buffer component))))

(defmethod print-method SpatialLTM [comp ^java.io.Writer w]
  (.write w (format "SpatialLTM{}")))

(defn start [& {:as args}]
  (let [p (merge-parameters args)]
    (->SpatialLTM (atom nil) (atom #{}))))