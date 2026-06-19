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
[Incubating] Problems report is available at: file:///home/runner/work/Friday/Friday/build/reports/problems/problems-report.html
FAILURE: Build failed with an exception.
* What went wrong:
org.gradle.api.InvalidUserDataException: Invalid TOML catalog definition:
  - Problem: In version catalog libs, parsing failed with 1 error.
    
    Reason: In file '/home/runner/work/Friday/Friday/gradle/libs.versions.toml' at line 63, column 1: Unexpected '\r', expected a newline or end-of-input.
    
    Possible solution: Fix the TOML file according to the syntax described at https://toml.io.
    
    For more information, please refer to https://docs.gradle.org/9.1.0/userguide/version_catalog_problems.html#toml_syntax_error in the Gradle documentation.
> Invalid TOML catalog definition:
    - Problem: In version catalog libs, parsing failed with 1 error.
      
      Reason: In file '/home/runner/work/Friday/Friday/gradle/libs.versions.toml' at line 63, column 1: Unexpected '\r', expected a newline or end-of-input.
      
      Possible solution: Fix the TOML file according to the syntax described at https://toml.io.
      
      For more information, please refer to https://docs.gradle.org/9.1.0/userguide/version_catalog_problems.html#toml_syntax_error in the Gradle documentation.
* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to generate a Build Scan (Powered by Develocity).
> Get more help at https://help.gradle.org.
BUILD FAILED in 25s
Error: Process completed with exit code 1.