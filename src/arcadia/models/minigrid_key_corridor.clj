(ns arcadia.models.minigrid-key-corridor
  "Minigrid integration model for demo and development purposes."
  (:require [arcadia.utility.model :as model]
            [arcadia.utility.display :refer [i#]]
            [arcadia.utility.descriptors :as d]
            [arcadia.utility.opencv :as cv]
            [arcadia.architecture.registry :refer [get-sensor]]
            [arcadia.models.configurations.minigrid :as mgc]
            [arcadia.models.schemas.minigrid :as mgt] 
            [arcadia.models.core]
            [clojure.data.generators :as dgen]
            [clojure.string]
            [clojure.java.io]))

;; How to run the model. This is configured "find-only" behavior. Change plan-find to 
;; either plan-recall or plan-both for different operation. 800 cycles should be plenty
;; of time to complete the task in the default environment.
;;
;; (binding [arcadia.models.minigrid-key-corridor/*plan* arcadia.models.minigrid-key-corridor/plan-find] 
;;   (arcadia.models.minigrid-key-corridor/example-run 800 "MiniGrid-KeyCorridorS4R3-v0" false))

(def ^:private default-environment "MiniGrid-KeyCorridorS4R3-v0")
;; simple version, no keys, no locked doors.
;; "MiniGrid-MultiRoom-N6-v0"

;; Rely solely on spotting items in the right order as you move through the maze. 
(def plan-find {:condition "find"
                :plan [mgc/plan-explore
                       mgc/plan-navigate-key mgc/plan-navigate-door
                       mgc/plan-find-door mgc/plan-find-key]})

;; Rely on having seen items as you go through the maze
(def plan-recall {:condition "recall"
                  :plan [mgc/plan-explore
                         mgc/plan-navigate-key mgc/plan-navigate-door
                         mgc/plan-recall-door mgc/plan-recall-key]})

;; Combine active search with memory
(def plan-both {:condition "both"
                :plan [mgc/plan-explore
                       mgc/plan-navigate-key mgc/plan-navigate-door #_mgc/plan-navigate-goal
                       mgc/plan-find-door mgc/plan-find-key #_mgc/plan-find-goal
                       mgc/plan-recall-door mgc/plan-recall-key #_mgc/plan-recall-goal]})

;; enables swapping of configurations
(def ^:private ^:dynamic *plan* plan-both)

(def ^:private object-descriptors [mgt/door-descriptor mgt/key-descriptor mgt/goal-descriptor mgt/ball-descriptor])
(def ^:private boundary-descriptors  [mgt/toggle-descriptor mgt/entry-descriptor])

(defn- event-string [e]
  (if (= (-> e :arguments :event-name) "action")
    (if (= (-> e :arguments :action-command) "toggle")
      "open-door"
      (-> e :arguments :action-command))
    (-> e :arguments :event-name)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)
   (model/add minigrid-sensor)))

#_{:clj-kondo/ignore [:unresolved-symbol]}
(defn component-setup []
  (model/setup
   ;; minigrid specific
   (model/add minigrid.perception {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.actor)
   (model/add minigrid.segmenter {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.mapper {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.highlighter)
   (model/add minigrid.egocentric-map {:sensor (get-sensor :minigrid-sensor)})
   (model/add minigrid.episode-binder {:episode-boundaries boundary-descriptors})
   (model/add minigrid.action-detector)
   (model/add minigrid.inventory)
   (model/add minigrid.enter-room-detector)
   (model/add minigrid.affordances)
   (model/add minigrid.infogain-enhancer)
   (model/add minigrid.object-path-enhancer)
   (model/add minigrid.weight-combiner)

   ;; task management
   (model/add sr-link-processor)
   (model/add initial-response-generator)

   ;; memory management
   (model/add spatial-ltm)
   (model/add episodic-ltm {:episode-boundaries boundary-descriptors})
   (model/add conceptual-ltm)
   (model/add event-memory {:event-lifespan 1 :descriptors object-descriptors})
   (model/add affordance-tracker)
   (model/add object-found-detector)

   ;; new control components
   (model/add prospective-memory)
   (model/add outcome-memory)
   (model/add plan-infuser {:initial-plan (:plan *plan*)})
   (model/add scheduler {:active-cycles 10})

   ;; "visual" processing
   (model/add object-file-binder)
   (model/add vstm)
   (model/add object-locator {:sensor (get-sensor :minigrid-sensor)
                              :use-hats? false})

   ;; information display
   (model/add display.controls {:x 100 :y 800})

   (model/add display
              {:display-name "Episode Events"
               :x 700 :y 50
               :panel-width 300
               :elements
               [["Current"
                 (i# (-> % :content (d/first-element :name "episode" :type "instance" :world nil)
                         :arguments :temporal
                         (#(map event-string %))))]
                ["Recalled"
                 (i# (-> % :content (d/first-element :name "episode" :type "instance" :world "episodic-memory")
                         :arguments :temporal
                         (#(map event-string %))))]]}
              :event-display)


   (model/add display
              {:display-name "Navigation Goal"
               :x 700 :y 250
               :panel-width 400
               :panel-height 50
               :elements
               [["Object"
                 (i# (-> % :content (d/first-element :name "minigrid-navigation" :type "action" :world nil)
                         :arguments :goal :arguments (#(select-keys % [:category :state :color]))))]
                ["Location"
                 (i# (-> % :content (d/first-element :name "minigrid-navigation" :type "action" :world nil)
                         :arguments :goal :arguments :region (#(select-keys % [:x :y]))))]]}
              :navigation-display)

   (model/add display
              {:display-name "State"
               :x 700 :y 150
               :panel-width 400
               :elements
               [["Cycle" (i# (-> % :cycle))]
                ["Task"
                 (i# (-> % :content (d/first-element :name "task" :type "instance" :world "task-wm")
                         :arguments :handle))]
                ["Focal Object"
                 (i# (if (d/element-matches? (:focus %) :name "object" :world nil)
                       (select-keys (-> % :focus :arguments) [:category :state :color])
                       (:name (:focus %))))]]}
              :state-display)

   (model/add display.scratchpad {:panel-width 600 :panel-height 400 :x 700 :y 450
                                  :flatten-elements? false
                                  :instant-updates? true
                                  ;; for debugging, you can pause the model when different elements appear in content or 
                                  ;; as the focus. examples are shown here. uncomment to use one or more of them.
                                  :elements [#_["NA" (arcadia.utility.display/pause-fn
                                                     (-> % :focus vector (d/first-element :name "navigation-affordance")))]
                                             #_["OFE" (arcadia.utility.display/pause-fn
                                                       (-> % :content (d/first-element :name "event" :event-name "object-found"
                                                                                       :object (fn [x] (d/element-matches? x :category "door")))))]
                                             #_["Recall" (arcadia.utility.display/pause-fn
                                                          (-> % :content (d/first-element :name "episode"
                                                                                          :query "what"
                                                                                          :world "episodic-memory")))]
                                             #_["Failure" (arcadia.utility.display/pause-fn
                                                           (-> % :content (d/first-element :name "query-failure"
                                                                                           :world "episodic-memory")))]]})

   (model/add display
              {:panels [[""
                         (i# (:image %))]
                        #_["Map Grid"
                         (i# (-> % :content (d/first-element :name "spatial-map" :perspective "allocentric") :arguments :layout
                                 (cv/in-range [2 0 0] [10 10 10])
                                 (cv/cvt-color cv/COLOR_GRAY2BGR)
                                 (cv/convert-to cv/CV_8S)
                                 ))]
                        #_["Current Episode"
                         (i# (-> % :content (d/first-element :name "map-grid") :arguments :map
                                 (cv/in-range [2 0 0] [2 10 10])
                                 (cv/cvt-color cv/COLOR_GRAY2BGR)
                                 (cv/convert-to cv/CV_8S)
                                 ))
                         :glyphs [[(i# (-> % :content (d/first-element :name "episode" :world nil)
                                           :arguments :spatial :contents
                                           (#(map (fn [k] (vector k (first (get % k)))) (keys %)))))
                                   :glyph-value (i# (-> % :glyph first))
                                   :color (i# (-> % :glyph second :arguments :color))
                                   :fill-color (i# (when (#{"closed" "locked"} (-> % :glyph second :arguments :state))
                                                     (-> % :glyph second :arguments :color)))]]]
                        #_["Recalled Episode"
                         (i# (-> % :content (d/first-element :name "map-grid") :arguments :map
                                 (cv/in-range [2 0 0] [2 10 10])
                                 (cv/cvt-color cv/COLOR_GRAY2BGR)
                                 (cv/convert-to cv/CV_8S)
                                 ))
                         :glyphs [[(i# (-> % :content (d/first-element :name "episode" :world "episodic-memory")
                                           :arguments :spatial :contents
                                           (#(map (fn [k] (vector k (first (get % k)))) (keys %)))))
                                   :glyph-value (i# (-> % :glyph first))
                                   :color (i# (-> % :glyph second :arguments :color))
                                   :fill-color (i# (when (#{"closed" "locked"} (-> % :glyph second :arguments :state))
                                                     (-> % :glyph second :arguments :color)))]]]]

               :element-type :image
               :image-width 300
               :image-height 300
               :sensor (get-sensor :minigrid-sensor)
               :display-name "Minigrid" :x 50 :y 50})))

(defn example-run
  [cycles & {:keys [deterministic? disable-displays? env-seed env-str record?]
             :or {deterministic? false
                  disable-displays? false 
                  env-str default-environment
                  env-seed nil
                  record? false}}]
   ;; use resolve to avoid circular dependencies for the example run
  ((resolve 'arcadia.core/startup)
   'arcadia.models.minigrid-key-corridor 'arcadia.simulator.environment.minigrid2
   {:environment env-str
    :random-seed env-seed}
   cycles
    ;; for debugging, you can turn on deterministic behavior. if you want, you
    ;; can also change the default random seed.
    ; :random-seed 3
   :deterministic? deterministic?
   :disable-displays? disable-displays?
   :record? record?
    ;; for debugging, if you want to stop the model on a particular cycle to begin
    ;; inspection, you can use this parameter. just change 238 to whatever cycle you 
    ;; want to pause on. 
    ;; NOTE: do not disable displays if you do this or you will be stuck. 
    ; :cycles-before-pause 238
   ))

(defn run-trial 
  "Run a single experimental trial and produce a CSV string containing idx, the number of cycles,
  and the random seed used to create the Minigrid environment."
  [idx & {:keys [seed]}]
  (let [s (clojure.string/split-lines
           (with-out-str (arcadia.models.minigrid-key-corridor/example-run
                          800 
                          {:env-str "MiniGrid-KeyCorridorS4R3-v0"
                           :env-seed seed
                           :record false
                           :disable-displays? true
                           :deterministic? true})))]
    (str idx ","
         (second (clojure.string/split
                  (second (reverse s)) #" "))
         ","
         (nth (clojure.string/split (first s) #" ") 2))))

(defn run-trials 
  "Run n trials, recording data to file f, using plan configurations p.
   Example: (run-trials 50 \"foo.out\" plan-find)"
  [n f p s]
  (binding [*plan* p]
    (spit f "index,cycles,env-seed\n") 
    #_(println (:condition *plan*) n f)
    (loop [seeds s
           indices (range n)]
      (when (seq seeds)
        (spit f (str (run-trial (first indices) :seed (first seeds)) "\n") :append true)
        (recur (rest seeds) (rest indices))))))

(defn run-sync-experiment [n]
  (let [random-seeds (doall (repeatedly n #(dgen/uniform 0 Integer/MAX_VALUE)))]
    (doall 
     (pmap #(run-trials n %2 %1 random-seeds)
           [plan-find plan-recall plan-both] ["mfind.csv" "mrecall.csv" "mboth.csv"]))
    nil)) 