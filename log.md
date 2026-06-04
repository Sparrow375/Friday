Run ./gradlew assembleDebug
Downloading https://services.gradle.org/distributions/gradle-9.1.0-bin.zip
................................................................................................................................
Unzipping /home/runner/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0-bin.zip to /home/runner/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3
Set executable permissions for: /home/runner/.gradle/wrapper/dists/gradle-9.1.0-bin/9agqghryom9wkf8r80qlhnts3/gradle-9.1.0/bin/gradle
Welcome to Gradle 9.1.0!
Here are the highlights of this release:
 - Full Java 25 support
 - Native task graph visualization
 - Enhanced console output
For more details see https://docs.gradle.org/9.1.0/release-notes.html
Starting a Gradle Daemon (subsequent builds will be faster)
Calculating task graph as no cached configuration is available for tasks: assembleDebug
Configuration cache entry stored.
FAILURE: Build failed with an exception.
* Where:
Build file '/home/runner/work/Friday/Friday/build.gradle.kts' line: 2
* What went wrong:
Plugin [id: 'com.google.devtools.ksp', version: '2.2.10-2.0.0', apply: false] was not found in any of the following sources:
- Gradle Core Plugins (plugin is not in 'org.gradle' namespace)
- Included Builds (No included builds contain this plugin)
- Plugin Repositories (could not resolve plugin artifact 'com.google.devtools.ksp:com.google.devtools.ksp.gradle.plugin:2.2.10-2.0.0')
  Searched in the following repositories:
    Google
    MavenRepo
    Gradle Central Plugin Repository
* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to generate a Build Scan (Powered by Develocity).
> Get more help at https://help.gradle.org.
BUILD FAILED in 27s
Error: Process completed with exit code 1.