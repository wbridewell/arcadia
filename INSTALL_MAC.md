Setting up an ARCADIA development environment
=============================================

Clojure is the primary language for ARCADIA development, although components can be written in any language that is accessible through a wrapper in the Java Native Interface. Creating a Clojure development environment is fairly straightforward.

Installing software
=============================================

1.  Install the latest version of the Java Developers Kit.
    1.  This software is available from [Oracle](http://www.oracle.com/technetwork/java/javase/downloads/index.html) for a variety of platforms. Supposing you've downloaded it to your downloads directory on macOS...
    2.  `cd ~/Downloads`  
        `tar xf openjdk-<version>_osx-x64_bin.tar.gz`  
        `sudo mv jdk-<version>.jdk /Library/Java/JavaVirtualMachines/`
        
        Run `java -version` to make sure the latest JDK is being used. 
        
2.  Install [Leiningen](http://leiningen.org) for your platform.
    1.  If you are working on Linux, the software is likely in your package manager.
    2.  On macOS, you are encouraged to use the [Homebrew](http://brew.sh) package manager, which includes a Leiningen package.
3.  Install the [Git](http://git-scm.com) version control software.
    1.  This is readily available in package managers for Linux and macOS.
4.  Install the [Maven](http://maven.apache.org) tool for Java project management.
    1.  This is readily available in package managers for Linux and macOS.
5.  Retrieve the base ARCADIA workspace from the project's Git repository.
    1.  See detailed instructions below which also include details on getting OpenCV.
6.  Install the [Tesseract OCR](https://code.google.com/p/tesseract-ocr/) system and the [Tess4J](http://tess4j.sourceforge.net) Java wrapper. 
    1.  On macOS, you can use Homebrew to install Tesseract. On Linux, the software is likely available in your package manager.
    2.  Tess4J should be automatically added to your repository by Leiningen when you start your Clojure REPL. Check your project.clj file for the line `[net.sourceforge.tess4j/tess4j "4.4.1"]`.
    1.  Recent versions of Tess4J may have problems on macOS because the native library for tesseract is not included in the jar file. If you find this to be the case, see [Stack Overflow](https://stackoverflow.com/questions/21394537/tess4j-unsatisfied-link-error-on-mac-os-x) for the solution.
7.  Install [jbox2d](https://github.com/jbox2d/jbox2d) for your platform.

Setting up the git repository
-----------------------------

1.  Change to the directory that will contain your Clojure projects (ensure that the pathname does not include any spaces).
2.  `lein new arcadia --force`
    
3.  `cd arcadia`
    
4.  Setup the git repository. This is a little different because we're using an existing directory.
    1.  `git init`
        
    2.  `git remote add origin https://your.name@bitbucket.di2e.net/scm/arcadia/arcadia.git`
        
    3.  `git fetch`
        
    4.  `rm README.md project.clj src/arcadia/core.clj LICENSE .gitignore`
        
    5.  `git checkout -t origin/master`
        

Setting up OpenCV / Updating OpenCV
-----------------------------------

The repository includes a Leiningen project tree, a project file, and source code for ARCADIA. However, you will be required to build install certain development libraries and add files to your local repository. Although there are efforts to incorporate [CMU Sphinx](http://cmusphinx.sourceforge.net) and [ZeroMQ](http://zeromq.org), the principle requirement at this time is [OpenCV](http://docs.opencv.org).

Source code for OpenCV is readily available, but on macOS, the Homebrew package is the easiest to prepare.

The following should be done in your arcadia directory. (**Important**: These instructions are for version 4.2.0\_3. Change this number to the version you have installed when following them.)

1.  `brew update`
    
2.  Unless you have already done so, install Apache Ant
    
    brew install ant
    
3.  `brew uninstall --force opencv`
    
4.  `brew edit opencv`
    
    1.  Change line
        
    
    `-DBUILD_opencv_java=OFF`
    
              to
    
    `-DBUILD_opencv_java=ON`
    
5.  `brew install --build-from-source opencv`
    
6.  `cp /usr/local/Cellar/opencv/4.2.0_3/share/java/opencv4/opencv-420.jar ./opencv.jar`
    
7.  `cp /usr/local/Cellar/opencv/4.2.0_3/share/java/opencv4/libopencv_java420.dylib .`
    
8.  Create the native code JAR for OpenCV
    
    1.  For macOS, if you don't have a previous version
        
        `mkdir -p native/macosx/x86\_64`
        
        `mkdir -p target/native/macosx/x86\_64`
    2.  if you do
        
        `mv libopencv_java420.dylib native/macosx/x86_64/`
        
        `cp native/macosx/x86_64/libopencv_java420.dylib target/native/macosx/x86_64/`   
        
        (This step is supposed to happen automatically, but you may need to do it manually.)`
    3.  `jar -cMf opencv-native.jar native`
        
9.  Deploy the JAR files using Maven
    1.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv -Dversion=arcadia -Dpackaging=jar -Dfile=opencv.jar -Durl=file:repo`
        
    2.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=opencv-native -Dversion=arcadia -Dpackaging=jar -Dfile=opencv-native.jar -Durl=file:repo`
        
10.  Ensure that your project.clj file contains a reference to the OpenCV libraries under `:dependencies`.

        1.  `[local/opencv "arcadia"]`
        
        2.  `[local/opencv-native "arcadia"]`
        
11.  remove the old versions of the libraries from your local maven cache  
    `rm -r ~/.m2/repository/local/opencv*`
12.  `lein deps`
    
13.  `lein repl`
    
14.  `(load "arcadia/core")`
    

Setting up jbox2d
-----------------

The current version of arcadia requires that you also have jbox2d installed.

1.  Download [jbox2d](https://github.com/jbox2d/jbox2d) for your platform.
    
2.  Unzip the downloaded files.
    
3.  Navigate to wherever you placed jbox2d and into the jbox2d-library
4.  Fix the file pom.xml at this location
    1.  Add `<source>1.8</source>`, followed by `<target>1.8></target>` just above "`<excludes>`..."
5.  In a terminal, navigate to jbox2d-library and run 
    
    1.  `mvn install`
        
6.  Copy the file found in jbox2d-library/target to your arcadia directory.
    
7.  Navigate to your arcadia directory in a terminal.
    
8.  `mv jbox2d-library-2.3.1-SNAPSHOT.jar jbox2d.jar`
    
    1.  Substitute in the appropriate version number.
        
9.  `mvn deploy:deploy-file -DgroupId=local -DartifactId=jbox2d -Dversion=2.3.1 -Dpackaging=jar -Dfile=jbox2d.jar -Durl=file:repo`