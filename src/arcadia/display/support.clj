(ns
  ^{:doc "Helper functions for debug display components."}
  arcadia.display.support
  (:require [arcadia.architecture.registry :as reg]
            [arcadia.utility [general :as g] [parameters :as p]]))

(def ^:private debug-namespace
  "Namespace for the debug display component."
  'arcadia.component.display)

(defn initialize-debug-data
  "Initialize a debug data structure that will be used to record some information
   about the state of ARCADIA. If sensor is provided, it will be polled to
   produce an :image on each cycle. If sensor-hash, a hashmap of sensors, is provided,
   it will be used to produce :image-hash, a hashmap of images, on each cycle."
  ([params]
   (initialize-debug-data params {}))
  ([params additional-data]
   (atom (merge additional-data
                {:cycle -1 :sensor-delay (or (:sensor-delay params) 0)
                 :sensor (:sensor params) :sensor-hash (:sensor-hash params)
                 :sensor-buffer nil
                 :sensor-hash-buffer nil}))))

(defn update-debug-data!
  "Updates the data structure that is used to record info about the state of
   ARCADIA."
  [data]
  (swap! data update :cycle inc)
  (when-let [image (some-> data deref :sensor ((resolve 'arcadia.sensor.core/poll))
                           :image)]
    (swap! data update :sensor-buffer #(doall (take (+ 1 (:sensor-delay @data))
                                                    (conj % image)))))
  (when-let [images (some-> data deref :sensor-hash
                            (g/update-all #(-> % ((resolve 'arcadia.sensor.core/poll))
                                               :image)))]
    (swap! data update :sensor-hash-buffer #(doall (take (+ 1 (:sensor-delay @data))
                                                         (conj % images))))))

(defn debug-data->state
  "Converts debug data into a hashmap describing the current ARCADIA state. Note
   that we want the previous cycle number, not the current cycle, because we're
   describing the state from the previous cycle."
  [data]
  {:cycle (dec (:cycle @data))
   :image (nth (:sensor-buffer @data) (:sensor-delay @data) nil)
   :image-hash (nth (:sensor-hash-buffer @data) (:sensor-delay @data) nil)})

(defn- setup-state
  "Creates the hashmap holding the current state."
  [focus content registry debug-data] 
  (merge {:focus focus :content content :registry registry} (debug-data->state debug-data)))

(defn caption-string
  "Creates the string that will depict a caption, using metadata to describe an
   element's source if the paramters warrant it."
  [{caption :caption metadata :metadata params :params :as element-full}]
  (let [caption? (contains? element-full :caption)
        source
        (when (:show-sources? params)
          (g/when-let* [file (:file metadata)
                        line (:line metadata)]
                       (str "<span style='color:rgb(120,120,120)'>"
                            (subs file (inc (.lastIndexOf file "/"))) ":" line "  "
                            "</span>")))]
    (cond
      (and caption? source)
      (str caption ":\n" source)

      caption?
      (str caption ":")

      :else
      source)))

(defn- cycle-or-repeat
  "Creates a lazy, infinite sequence in which items are cycling. If items has a
   :final-value defined in its meta-data, then we don't want to cycle
   the sequence forever. Instead, we just want to run it once and then repeat the
   final item forever."
  [items]
  (if (-> items meta :final-value)
    (concat items (repeat (-> items meta :final-value)))
    (cycle items)))

(defn- cycle-hashmap
  "Takes a hashmap where each value is a list. Returns an infinite, lazy sequence of
   hashmaps that cycle through the values in the lists. Returns nil if the hashmap
   is empty."
  ([hash] (when (seq hash) (cycle-hashmap (g/update-all hash cycle-or-repeat) 0)))
  ([hash n] (lazy-seq (cons (g/update-all hash #(nth % n))
                            (cycle-hashmap hash (+ n 1))))))

(defn- flatten-items
  "Takes info, a hashmap of the form {keyfn items :params params} and produces
   a list of hashes, one for each element in the elements list. If any parameter
   values are lists created with the map-to-keyfn function, map those values
   across the list of items."
  [info keyfn map-to-keyfn]
  (let [items (keyfn info)
        params (:params info)]
    (if-let [new-updates (-> params
                             (g/filter-vals #(-> % meta map-to-keyfn))
                             (cycle-hashmap))]
      (map  #(assoc info keyfn %1 :params (merge params %2))
            items new-updates)
      (map #(assoc info keyfn % :params params) items))))

(declare resolve-value)

(defn- resolvable?
  "Returns true if an information function can be resolved, given the current
   state. As an example, if the information function refers to the current
   :element, then there must be an :element in the state."
  [ifn state]
  (every? #(contains? state %) (-> ifn meta :referents)))

(defn unresolved?
  "Returns true if an element still needs to be resolved"
  [elem]
  (or (-> elem meta :unresolved?)
      false))

(defn- update-progression!
  "Takes a vector containing a progression atom of the form
   [current-value [check next-value] [check next-value] ...] Resolves the first
   check to determine if it's time to progress to the first next-value. Returns
   the input if there's currently insufficient information in the state to
   determine whether it's time to progress."
  [[progression :as original] state]
  (let [[checker result] (second @progression)
        check (resolve-value checker state)]
    (cond
      (-> check meta :unresolved?) ;;Failed to resolve, try again later
      original

      check ;;Resolved to true
      (first (reset! progression (->> @progression rest rest (cons result))))

      :else
      (first @progression))))

(defn- resolve-value
  "Returns an element's current value, given the ARCADIA state. Can be called
   three ways:
   1) [item state parent keyfn] => add the parent to the state with key keyfn
   2) [item state] => behave normally
   3) [item] => the state is currently unavailable, so don't resolve information fns"
  ([item state parent keyfn]
   (resolve-value item (assoc state keyfn (keyfn parent))))
  ([item state]
   (loop [e item]
     (cond
       (-> e meta :progression?)
       (let [result (update-progression! e state)]
         (if (= result e)
           e
           (recur result)))

       (-> e meta :index)
       (let [index (resolve-value (-> e meta :index) state)]
         (cond
           (-> index meta :unresolved?)
           e

           (and (number? index)
                (or (< index (count e))
                    (-> e meta (contains? :final-value) not)))
           (->> (mod (int index) (count e)) (get e) recur)

           :else
           (-> e meta :final-value recur)))

       (and (-> e meta :map-to-panels?) (contains? state :panel))
       (recur (first e))
       (and (-> e meta :map-to-elements?) (contains? state :element))
       (recur (first e))
       (and (-> e meta :map-to-glyphs?) (contains? state :glyph))
       (recur (first e))

       (-> e meta :previous)
       (if (resolvable? e state)
         (let [prev (-> e meta :previous)
               result (e (assoc state :previous @prev))]
           (recur (reset! prev result)))
         e)
       
       (-> e meta :pause-function?)
       (if (resolvable? e state)
         (when (some? (e state))
           (some-> state :registry reg/pause) 
           "Pausing...")
         e)

       (-> e meta :information-function?)
       (if (resolvable? e state)
         (recur (e state))
         e)

       :else
       e)))
  ([item]
   (loop [e item]
     (cond
       (-> e meta :progression?)
       (recur (-> e first deref first))

       (-> e meta :index)
       (let [index (-> e meta :index)]
         (recur (if (number? index)
                  (or (and (>= index (count e))
                           (-> e meta :final-value))
                      (get e (mod (int index) (count e))))
                  (-> e meta :final-value))))

       (-> e meta :map-to-panels?)
       (recur (first e))
       (-> e meta :map-to-elements?)
       (recur (first e))
       (-> e meta :map-to-glyphs?)
       (recur (first e))
       (-> e meta :previous)
       (recur (-> e meta :previous deref))
       (-> e meta :information-function?)
       nil
       :else
       e))))

(defn resolve-glyphs
  "Get the glphs, for a particular element, given the current parameters and state,
   resolving the glyphs' values and parameters."
  [params state element]
  (let [state (assoc state :element element)]
    (assoc
     params :glyphs
     (->> (concat (:initial-glyphs params) (:glyphs params) (:runtime-glyphs params))

          (map #(update % :glyph resolve-value state))
          (map #(update % :params (fn [myparams] (merge params myparams))))

          (mapcat #(if (-> % :glyph seq?)
                     (flatten-items % :glyph :map-to-glyphs?)
                     (list %)))

          (remove #(-> % :glyph nil?))

          (map #(update % :params g/update-all resolve-value
                        (assoc state :glyph (:glyph %))))
          (map #(assoc % :glyph (-> % :params :glyph-value)))
          (remove #(-> % :glyph nil?))))))

(defn resolve-elements
  "Get the elements for a particular panel, given the current parameters and state,
   resolving the elements' values and parameters. If no panel or state is
   provided then don't bother resolving values and parameters. Just set up the
   data structures for each element (each element inherits parameters)."
  ([params]
   (assoc
    params :elements
    (->> (concat (:initial-elements params) (:elements params)
                 (:runtime-elements params))
         (map #(update % :params (fn [myparams] (merge params myparams))))
         (map #(update % :params g/update-all resolve-value)))))
  ([params state panel]
   (let [state  (assoc state :panel panel)]
     (assoc
      params :elements
      (->> (concat (:initial-elements params) (:elements params)
                   (:runtime-elements params))

           (map #(update % :element resolve-value state))
           (map #(update % :params (fn [myparams] (merge params myparams))))

           (mapcat #(if (and (-> % :element seq?) (-> % :params :flatten-elements?))
                      (flatten-items % :element :map-to-elements?)
                      (list %)))

           (map #(cond-> % (contains? % :caption)
                         (update :caption resolve-value state % :element)))

           (remove #(and (-> % :element nil?) (-> % :params :flatten-elements?)))

           (map #(update % :params g/update-all resolve-value
                         (assoc state :element (:element %))))
           (map #(assoc % :element (-> % :params :element-value)))
           (remove #(and (-> % :element nil?) (-> % :params :flatten-elements?)))
           (map #(update % :params resolve-glyphs state (:element %))))))))

(defn resolve-panels
  "Get the panels, given the current ARCADIA state,
   resolving the panels' values and parameters. If no state is
   provided then don't bother resolving values and parameters. Just set up the
   data structures for each contasiner (each panel inherits parameters)."
  ([params]
   (assoc
    params :panels
    (->> (concat (:initial-panels params) (:panels params)
                 (:runtime-panels params))
         (#(if (empty? %)
             [{:panel nil :header-expected? (:headers? params)}]
             %))
         (map #(update % :params (fn [myparams] (merge params myparams))))
         (map #(update % :params g/update-all resolve-value))
         (map #(update % :params resolve-elements)))))
  ([params state]
   (assoc
    params :panels
    (when (or (>= (:cycle state) 0) (:instant-updates? params))
      (->> (concat (:initial-panels params) (:panels params)
                   (:runtime-panels params))

           (map #(update % :panel resolve-value state))
           (map #(update % :params (fn [myparams] (merge params myparams))))

           (mapcat #(if (and (-> % :panel seq?) (-> % :params :flatten-panels?))
                      (flatten-items % :panel :map-to-panels?)
                      (list %)))

           (map #(cond-> % (contains? % :header)
                         (update :header resolve-value state % :panel)))

           (remove #(and (-> % :panel nil?) (-> % :params :flatten-panels?)))

           (map #(update % :params g/update-all resolve-value
                         (assoc state :panel (:panel %))))
           (remove #(and (-> % :panel nil?) (-> % :params :flatten-panels?)))
           (map #(update % :params resolve-elements state (:panel %))))))))

(defn resolve-parameters
  "Resolves all parameter values given the current ARCADIA state, if any. The
   state can be provided either as a hashmap or as the focus, content, registry, 
   and a debug-data structure."
  ([params]
   (resolve-panels params))
  ([params focus content registry debug-data]
   (resolve-parameters params (setup-state focus content registry debug-data)))
  ([params state]
   (-> params
       (g/update-all resolve-value state)
       (resolve-panels state))))

(defn merge-params-into-info
  "Given an initialized item of information (for a panel, element, or glyph),
   update its params by merging them into base-params."
  [info base-params]
  (update info :params #(merge base-params %)))

(declare initialize-parameters)

(defn initialize-panel
  "Given the arguments for a panel, in the form [optional-header panel
   optional-param1 optional-value1 optional-param2 optional-value2...], build a
   hashmap describing the panel, its header (if any), and its parameters. If
   check-params? is true, then ensure that the parameters for each panel are
   defined in the debug display namespace."
  [check-params? & args]
  (if (even? (count args))
    {:header (first args) :panel (second args)
     :params (initialize-parameters check-params? (apply hash-map (nthrest args 2)))}
    {:panel (first args)
     :params (initialize-parameters check-params? (apply hash-map (nthrest args 1)))}))

(defn initialize-element
  "Given the arguments for an element, in the form [optional-caption element
   optional-param1 optional-value1 optional-param2 optional-value2...], build a
   hashmap describing the element, its caption (if any), and its parameters. If
   check-params? is true, then ensure that the parameters for each element are
   defined in the debug display namespace.
   metadata may contains metadata about the code used to create this particular
   element (e.g., where it is)."
  [check-params? metadata & args]
  (if (even? (count args))
    {:caption (first args) :element (second args)
     :metadata (or metadata (meta (second args)))
     :params (initialize-parameters check-params? (apply hash-map (nthrest args 2)))}
    {:element (first args)
     :metadata (or metadata (meta (first args)))
     :params (initialize-parameters check-params? (apply hash-map (nthrest args 1)))}))

(defn initialize-glyph
  "Given the arguments for a glyph, in the form [glyph optional-param1
   optional-value1 optional-param2 optional-value2...], build a hashmap
   describing the glyph and its parameters. If check-params? is true, then ensure
   that the parameters for each glyph are defined in the debug display namespace."
  [check-params? & args]
  {:glyph (first args)
   :params (initialize-parameters check-params? (apply hash-map (nthrest args 1)))})

(defn initialize-parameters
  "Initializes a debug display component's parameters by checking that all
   parameters are defined in the debug display namespace (if check-params? is
   true) and initializing all panels, elements, and glyphs."
  [check-params? params]
  (when check-params?
    (p/merge-parameter-maps params debug-namespace))
  (-> params
      (g/update-keys (filter #(contains? params %)
                             [:initial-panels :panels :runtime-panels])
                     #(map (fn [args] (apply initialize-panel check-params? args)) %))
      (g/update-keys (filter #(contains? params %)
                             [:initial-elements :elements :runtime-elements])
                     #(map (fn [args] (apply initialize-element check-params? nil args)) %))
      (g/update-keys (filter #(contains? params %)
                             [:initial-glyphs :glyphs :runtime-glyphs])
                     #(map (fn [args] (apply initialize-glyph check-params? args)) %))))

(defn clone-parameters
  "If a parameter value has an atom, clone it, so that we have separate copies
   for each initialization of this parameter value."
  [params]
  (g/careful-postwalk
   #(cond
      (-> % meta :progression?)
      (with-meta (-> % first deref clone-parameters atom vector) {:progression? true})

      (-> % meta :previous)
      (vary-meta % assoc :previous (atom (-> % meta :previous deref)))

      (-> % meta :paused?)
      (vary-meta % assoc :paused? (atom (-> % meta :paused? deref))
                 :stopped? (atom false) :stepped? (atom false)) 
      
      :else
      %)
   params))
