Run ./gradlew assembleDebug
Welcome to Gradle 9.1.0!
Here are the highlights of this release:
 - Full Java 25 support
 - Native task graph visualization
 - Enhanced console output
For more details see https://docs.gradle.org/9.1.0/release-notes.html
Starting a Gradle Daemon (subsequent builds will be faster)
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.
> Task :app:downloadWhisperModel
Whisper model already exists, skipping download.
> Task :app:preBuild
> Task :app:preDebugBuild
> Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
> Task :app:generateDebugResources
> Task :app:packageDebugResources
> Task :app:processDebugNavigationResources
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:javaPreCompileDebug
> Task :app:parseDebugLocalResources
> Task :app:mergeDebugAssets
> Task :app:desugarDebugFileDependencies
> Task :app:generateDebugRFile
> Task :app:checkDebugAarMetadata
> Task :app:compileDebugNavigationResources
> Task :app:mapDebugSourceSetPaths
> Task :app:compressDebugAssets
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:mergeDebugResources
> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:checkDebugDuplicateClasses
> Task :app:mergeLibDexDebug
> Task :app:preReleaseBuild
> Task :app:processDebugResources
> Task :app:configureCMakeRelease[arm64-v8a]
Checking the license for package CMake 3.22.1 in /usr/local/lib/android/sdk/licenses
License for package CMake 3.22.1 accepted.
Preparing "Install CMake 3.22.1 v.3.22.1".
"Install CMake 3.22.1 v.3.22.1" ready.
Installing CMake 3.22.1 in /usr/local/lib/android/sdk/cmake/3.22.1
"Install CMake 3.22.1 v.3.22.1" complete.
"Install CMake 3.22.1 v.3.22.1" finished.
> Task :app:kspDebugKotlin
> Task :app:mergeExtDexDebug
> Task :app:configureCMakeRelease[arm64-v8a]
C/C++: CMAKE_BUILD_TYPE=Release
> Task :app:compileDebugKotlin
> Task :app:buildCMakeRelease[arm64-v8a]
> Task :app:compileDebugKotlin FAILED
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:120:9 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:120:13 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:120:13 Function declaration must have a name.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:11 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:17 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:18 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:19 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:21 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:30 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:32 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:122:32 Function declaration must have a name.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:123:63 Unresolved reference 'e'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:131:19 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:135:19 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:136:13 Unresolved reference 'maxRmsSeen'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:140:25 Unresolved reference 'maxRmsSeen'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:140:37 Unresolved reference 'maxRmsSeen'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:146:19 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:151:19 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:158:13 Unresolved reference 'mainHandler'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:159:21 Unresolved reference 'isListeningEnabled'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:159:41 Unresolved reference 'startRecognizerInternal'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:166:23 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:170:17 Unresolved reference 'mainHandler'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:171:25 Unresolved reference 'isListeningEnabled'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:171:45 Unresolved reference 'startRecognizerInternal'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:179:23 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:192:13 Unresolved reference 'maxRmsSeen'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:193:19 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:193:84 Unresolved reference 'maxRmsSeen'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:198:30 Unresolved reference 'cachedVariants'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:204:29 Method 'iterator()' is ambiguous for this expression. Applicable candidates:
fun <T> Enumeration<T>.iterator(): Iterator<T>
fun <T> Iterator<T>.iterator(): Iterator<T>
fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>>
fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>>
fun CharSequence.iterator(): CharIterator
fun BufferedInputStream.iterator(): ByteIterator
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:213:33 Method 'iterator()' is ambiguous for this expression. Applicable candidates:
fun <T> Enumeration<T>.iterator(): Iterator<T>
fun <T> Iterator<T>.iterator(): Iterator<T>
fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>>
fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>>
fun CharSequence.iterator(): CharIterator
fun BufferedInputStream.iterator(): ByteIterator
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:217:31 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:217:104 Unresolved reference 'maxRmsSeen'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:240:30 Unresolved reference 'cachedVariants'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:246:29 Method 'iterator()' is ambiguous for this expression. Applicable candidates:
fun <T> Enumeration<T>.iterator(): Iterator<T>
fun <T> Iterator<T>.iterator(): Iterator<T>
fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>>
fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>>
fun CharSequence.iterator(): CharIterator
fun BufferedInputStream.iterator(): ByteIterator
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:250:38 'operator' modifier is required on 'FirNamedFunctionSymbol kotlin/compareTo' in 'compareTo'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:267:43 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:268:28 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:274:28 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:274:28 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:275:17 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:275:17 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:276:21 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:276:21 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:278:31 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:278:31 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:278:47 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:278:47 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:280:21 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:280:21 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:280:32 'operator' modifier is required on 'FirNamedFunctionSymbol kotlin/compareTo' in 'compareTo'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:280:51 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:280:51 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:286:16 Unresolved reference 'dpArray'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:286:16 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:290:9 Unresolved reference 'stopListening'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:291:9 Unresolved reference 'onWakeWordDetected'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:296:13 Unresolved reference 'speechRecognizer'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:297:13 Unresolved reference 'speechRecognizer'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:298:17 Unresolved reference 'sharedRecognizer'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:299:17 Unresolved reference 'speechRecognizer'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:302:19 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:304:13 Unresolved reference 'sharedRecognizer'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:305:13 Unresolved reference 'speechRecognizer'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/audio/WakeWordDetector.kt:323:1 Syntax error: Expecting a top level declaration.
27 actionable tasks: 27 executed
FAILURE: Build failed with an exception.
* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details
* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to generate a Build Scan (Powered by Develocity).
> Get more help at https://help.gradle.org.
BUILD FAILED in 3m 4s
Error: Process completed with exit code 1.