Data Channels
===
There are two primary ways that information flows to components in ARCADIA, through sensors and in accessible content.

## Sensors
To gather information from the environment, ARCADIA uses sensors that components can poll. These sensors can receive information encoded in modality-specific formats and organize that information into data structures that the components can interpret. Currently, the only sensor in the environment emulates a camera. That sensor, called `stable-viewpoint`, reads an image represented as an [OpenCV](https://opencv.org/) matrix and passes it along to any component that requests it.

To use a sensor, a model includes an `setup-sensors` function that takes the following form.

```Clojure
(defn sensor-setup []
  (model/setup
   (model/add stable-viewpoint)))
```

In this example, the `stable-viewpoint` sensor is initialized and stored in the registry. Although a model can contain multiple instances of the stable-viewpoint sensor, the parameters of the sensor are encoded within an environment so each instance will behave identically. Specifically, this sensor will ask the environment to render its state as a matrix.

```Clojure
(defrecord StableViewpoint [environment]
  Sensor
  (poll [sensor] (env/render environment "opencv-matrix" false)))
```

Sensors are passed to components that use them during a component's initialization.

```Clojure
;; Initializing a component with a registered instance of the stable-viewpoint sensor.
(model/add highlighter.saliency {:sensor (get-sensor :stable-viewpoint)})
```

To retrieve information from the sensor, the component calls the `poll` function.

```Clojure
 ;; Calling the poll function of a component's sensor.
(arcadia.sensor.core/poll (:sensor component))
```

## Accessible Content and Expectations

To do...
