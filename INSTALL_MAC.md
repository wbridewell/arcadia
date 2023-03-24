Setting up an ARCADIA development environment for macOS
=============================================

Clojure is the primary language for ARCADIA development, although components can be written in any language that is accessible through a wrapper in the Java Native Interface. Creating a Clojure development environment is fairly straightforward.

Installing software
=============================================

1.  Install the latest version of the Java Developers Kit (version 19.x supported)
    1.  This software is available from [Oracle](http://www.oracle.com/technetwork/java/javase/downloads/index.html) for a variety of platforms. Supposing you've downloaded it to your downloads directory on macOS...
    2.  `cd ~/Downloads`  
        `tar xf openjdk-<version>_osx-x64_bin.tar.gz`  
        `sudo mv jdk-<version>.jdk /Library/Java/JavaVirtualMachines/`
        
        Run `java -version` to make sure the latest JDK is being used. 
        
1.  Install the [Homebrew](http://brew.sh) package manager.
1.  Install required packages
    1. [Leiningen](http://leiningen.org) for Clojure project management.
    1. [Git](http://git-scm.com) version control software.
    1. [Maven](http://maven.apache.org) for Java project management.
    1. [Tesseract OCR](https://code.google.com/p/tesseract-ocr/) for OCR capabilities.
       1.  Recent versions of Tess4J may have problems on macOS because the native library for tesseract is not included in the jar file. If you find this to be the case, see [Stack Overflow](https://stackoverflow.com/questions/21394537/tess4j-unsatisfied-link-error-on-mac-os-x) for the solution.
    1. [Ant](https://ant.apache.org/) a Java build tool.
    1. `brew install leiningen git maven tesseract ant`
1.  Retrieve the base ARCADIA workspace from the project's Git repository.
    1.  See detailed instructions below which also include details on getting OpenCV.
1.  To use the Minigrid environment, install the Python packages.
    1. `pip install gymnasium==0.26.2 Minigrid==2.0.0`
    1. Gymnasium is a version of OpenAI Gym forked by Farama Foundation for Minigrid. See their [Github](https://github.com/Farama-Foundation/Minigrid) site.

Setting up the git repository from GitHub
-----------------------------

1.  Change to the directory that will contain your Clojure projects (ensure that the pathname does not include any spaces).
2.  `git clone https://github.com/wbridewell/arcadia.git`        

Setting up OpenCV / Updating OpenCV
-----------------------------------

The repository includes a Leiningen project tree, a project file, and source code for ARCADIA. However, you will be required to build install certain development libraries and add files to your local repository. The principle requirement at this time is [OpenCV](http://docs.opencv.org).

Source code for OpenCV is readily available, but on macOS, the Homebrew package is the easiest to set up.

The following should be done in your arcadia directory. (**Important**: These instructions are for version 4.7.0. Change this number to the version you have installed when following them.)

1.  The latest version of Homebrew requires the environment variable HOMEBREW_NO_INSTALL_FROM_API to be set to 1 in order to use locally edited formulas. This can be done by adding 
`export HOMEBREW_NO_INSTALL_FROM_API=1` to your .zshrc file or by executing it in the terminal 
before following these instructions.

1.  `brew install opencv`
    
1.  `brew edit opencv`
    
    1.  Change the line `-DBUILD_opencv_java=OFF` to `-DBUILD_opencv_java=ON`
    
1.  `brew reinstall --build-from-source opencv`

1.  The following commands differ depending on whether you are using an Apple Silicon or Intel Mac.
    1. Intel

      `cp /usr/local/Cellar/opencv/4.7.0/share/java/opencv4/opencv-470.jar ./opencv.jar`

      `cp /usr/local/Cellar/opencv/4.7.0/share/java/opencv4/libopencv_java470.dylib .`
    1. Apple Silicon

      `cp /opt/homebrew/Cellar/opencv/4.7.0/share/java/opencv4/opencv-470.jar ./opencv.jar`

      `cp /opt/homebrew/Cellar/opencv/4.7.0/share/java/opencv4/libopencv_java470.dylib .`
    
8.  Create the native code JAR for OpenCV
    
    1.  For macOS, if you don't have a previous version
        1. Intel 

        `mkdir -p native/macosx/x86_64`

        `mv libopencv_java470.dylib native/macosx/x86_64/`
        1. Apple Silicon

        `mkdir -p native/macosx/aarch64`

        `mv libopencv_java470.dylib native/macosx/aarch64/`
                
    1.  `jar -cMf opencv-native.jar native`
        
9.  Deploy the JAR files using Maven
    1.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv -Dversion=arcadia -Dpackaging=jar -Dfile=opencv.jar -Durl=file:repo`
        
    2.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv-native -Dversion=arcadia -Dpackaging=jar -Dfile=opencv-native.jar -Durl=file:repo`
        
10.  Ensure that your project.clj file contains a reference to the OpenCV libraries under `:dependencies`.

        1.  `[local/opencv "arcadia"]`
        
        2.  `[local/opencv-native "arcadia"]`
        
11.  remove the old versions of the libraries from your local maven cache  
    `rm -r ~/.m2/repository/local/opencv*`


Running ARCADIA
-----------------------------------

1.  `lein repl`
  1. To enable python interoperability, `lein with-profile python repl`
  1. For Apple Silicon, `lein with-profile aarch64 repl`
  1. For both, `lein with-profile python,aarch64 repl`
    
1.  Inside the REPL, `(refresh)`

1.  To run a test model, `(arcadia.models.mot-simple/example-run)`