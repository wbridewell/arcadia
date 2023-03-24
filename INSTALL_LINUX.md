
Setting up an ARCADIA development environment for Linux
=======================================================

(NOTE: Use these steps if you are setting up ARCADIA specifically on Linux. These steps worked for Ubuntu 22.04 and on the Windows Subsystem for Linux. Your mileage may vary. Substitute in the appropriate package management software for apt, and bear in mind that OpenCV may be installed elsewhere on your system.)

Clojure is the primary language for ARCADIA development, although components can be written in any language that is accessible through a wrapper in the Java Native Interface. Creating a Clojure development environment is fairly straightforward.

Installing software
-------------------

1.  Install the latest version of the Java Developers Kit.  
    1.  `sudo apt update`
    2.  `sudo apt install openjdk-19-jdk`
    3.  (JDK 19 is required. To see the versions currently available, try: `apt search openjdk`)
2.  Install [Leiningen](http://leiningen.org).
    1.  `sudo apt install leiningen`
3.  Install the [Git](http://git-scm.com) version control software.
    1.  Can use apt, but this is likely already installed.
4.  Install the [Maven](http://maven.apache.org) tool for Java project management.
    1.  `sudo apt install maven`
5.  Retrieve the base ARCADIA workspace from the project's Git repository.
    1.  See detailed instructions below which also include details on getting OpenCV.
6.  Install the [Tesseract OCR](https://code.google.com/p/tesseract-ocr/) system and the [Tess4J](http://tess4j.sourceforge.net) Java wrapper.  
    1.  Tess4J should be automatically added to your repository by Leiningen when you start your Clojure REPL. Check your project.clj file for the line `[net.sourceforge.tess4j/tess4j "5.2.1"]`.
7.  To use the Minigrid environment, install the Python packages.
    1. `pip install gymnasium==0.26.2 Minigrid==2.0.0`
    2. Gymnasium is a version of OpenAI Gym forked by Farama Foundation for Minigrid. See their [Github](https://github.com/Farama-Foundation/Minigrid) site.

Setting up the git repository from GitHub
-----------------------------

1.  Change to the directory that will contain your Clojure projects (ensure that the pathname does not include any spaces).
2.  `git clone https://github.com/wbridewell/arcadia.git`     

Setting up OpenCV / Updating OpenCV
-----------------------------------

The repository includes a Leiningen project tree, a project file, and source code for ARCADIA. However, you will be required to build install certain development libraries and add files to your local repository.

The following should be done in your arcadia directory. (**Important**: These instructions are for version 4.5.4. Change this number to the version you have installed when following them.)

1.  `sudo apt update`
    
2.  Unless you have already done so, install Apache Ant
    
    `sudo apt install ant`
    
3.  Uninstall your previous version of opencv, if desired.  
    `sudo apt remove (previous opencv name)`
4.  Install opencv for java  
    `apt search libopencv` (to see current versions)  
    `sudo apt install libopencv4.5d-jni`  (probably this version)
5.  `cp /usr/share/java/opencv4/opencv-454d.jar ./opencv.jar`
6.  `cp /usr/lib/jni/libopencv_java454d.so ./libopencv_java454.so` (note: the 'd' is removed)        
7.  `cp libopencv_java454d.so native/linux/x86_64/libopencv_java454.so target/native/linux/x86_64/` 
        (This step is supposed to happen automatically, but you likely need to do it manually.)

8.  `jar -cMf opencv-native.jar native`
    
9.  Deploy the JAR files using Maven
    1.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv -Dversion=arcadia -Dpackaging=jar -Dfile=opencv.jar -Durl=file:repo`
        
    2.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv-native -Dversion=arcadia -Dpackaging=jar -Dfile=opencv-native.jar -Durl=file:repo`
        
10.  Ensure that your project.clj file contains a reference to the OpenCV libraries under `:dependencies`.
    
        1.  `[local/opencv "arcadia"]`
            
        2.  `[local/opencv-native "arcadia"]`
        
11.  `lein deps`

Running ARCADIA
-----------------------------------

1.  `lein repl`
  1. To enable python interoperability, `lein with-profile python repl`
    
1.  Inside the REPL, `(refresh)`

1.  To run a test model, `(arcadia.models.mot-simple/example-run)`