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
> Task :app:parseDebugLocalResources
> Task :app:javaPreCompileDebug
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:generateDebugRFile
> Task :app:mergeDebugAssets
> Task :app:desugarDebugFileDependencies
> Task :app:checkDebugAarMetadata
> Task :app:compileDebugNavigationResources
> Task :app:mapDebugSourceSetPaths
> Task :app:compressDebugAssets
> Task :app:createDebugCompatibleScreenManifests
> Task :app:extractDeepLinksDebug
> Task :app:mergeDebugResources
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:preReleaseBuild
> Task :app:checkDebugDuplicateClasses
> Task :app:configureCMakeRelease[arm64-v8a]
Checking the license for package CMake 3.22.1 in /usr/local/lib/android/sdk/licenses
License for package CMake 3.22.1 accepted.
Preparing "Install CMake 3.22.1 v.3.22.1".
"Install CMake 3.22.1 v.3.22.1" ready.
Installing CMake 3.22.1 in /usr/local/lib/android/sdk/cmake/3.22.1
"Install CMake 3.22.1 v.3.22.1" complete.
"Install CMake 3.22.1 v.3.22.1" finished.
> Task :app:kspDebugKotlin
> Task :app:configureCMakeRelease[arm64-v8a]
C/C++: CMAKE_BUILD_TYPE=Release
> Task :app:processDebugManifestForPackage
> Task :app:processDebugResources
> Task :app:compileDebugKotlin
> Task :app:mergeExtDexDebug
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1115:18 Missing return statement.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:9 Syntax error: Expecting member declaration.
> Task :app:compileDebugKotlin
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:12 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:13 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:20 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:23 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:35 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:36 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:47 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:48 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:49 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:51 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1121:51 Function declaration must have a name.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1123:34 Suspend function 'suspend fun emit(value: String): Unit' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1126:43 Suspend function 'suspend fun loadModel(modelPath: String): Boolean' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1129:28 Return type mismatch: expected 'Unit', actual 'QueryResult'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1132:30 Suspend function 'suspend fun emit(value: String): Unit' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1133:47 Suspend function 'suspend fun buildMinimalPrompt(userInput: String): String' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1133:66 Unresolved reference 'resolvedInput'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1134:40 Suspend function 'suspend fun generateStream(prompt: String, maxTokens: Int = ..., temp: Float = ..., callback: LlamaEngine.TokenCallback): String' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1141:27 Suspend function 'suspend fun saveConversationTurn(userMessage: String, assistantResponse: String): Unit' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1141:48 Unresolved reference 'resolvedInput'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1142:20 Return type mismatch: expected 'Unit', actual 'QueryResult'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1143:11 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1143:16 Syntax error: Expecting member declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1143:16 Function declaration must have a name.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1144:17 Unresolved reference 'cleanQuery'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1144:53 Unresolved reference 'cleanQuery'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1144:89 Unresolved reference 'cleanQuery'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1144:123 Unresolved reference 'cleanQuery'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1144:156 Unresolved reference 'cleanQuery'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1145:34 Suspend function 'suspend fun emit(value: String): Unit' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1148:45 Suspend function 'suspend fun execute(args: JsonObject): ToolResult' can only be called from a coroutine or another suspend function.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1149:46 Unresolved reference 'resolvedInput'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1151:48 Return type mismatch: expected 'Unit', actual 'QueryResult'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1155:20 Return type mismatch: expected 'Unit', actual 'QueryResult'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1190:54 Unresolved reference 'TAG'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1194:21 Unresolved reference 'TOOL_CALL_REGEX'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1196:28 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1196:34 Unresolved reference 'groupValues'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1198:13 Unresolved reference 'TOOL_ARG_REGEX'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1198:56 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1199:27 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1199:36 Unresolved reference 'groupValues'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1200:29 Cannot infer type for this parameter. Specify it explicitly.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1200:38 Unresolved reference 'groupValues'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1225:29 Unresolved reference 'EMOJI_REGEX'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/AgentCore.kt:1227:1 Syntax error: Expecting a top level declaration.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/PostClassificationValidator.kt:72:47 Only safe (?.) or non-null asserted (!!.) calls are allowed on a nullable receiver of type 'ApplicationInfo?'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:181:36 Unresolved reference 'Document'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:184:25 Unresolved reference 'select'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:185:22 Method 'iterator()' is ambiguous for this expression. Applicable candidates:
fun <T> Enumeration<T>.iterator(): Iterator<T>
fun <T> Iterator<T>.iterator(): Iterator<T>
fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>>
fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>>
fun CharSequence.iterator(): CharIterator
fun BufferedInputStream.iterator(): ByteIterator
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:187:33 Unresolved reference 'text'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:189:31 Unresolved reference 'absUrl'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:191:32 Unresolved reference 'text'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:197:38 Unresolved reference 'select'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:198:26 Method 'iterator()' is ambiguous for this expression. Applicable candidates:
fun <T> Enumeration<T>.iterator(): Iterator<T>
fun <T> Iterator<T>.iterator(): Iterator<T>
fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>>
fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>>
fun CharSequence.iterator(): CharIterator
fun BufferedInputStream.iterator(): ByteIterator
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:209:40 Unresolved reference 'Document'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:212:25 Unresolved reference 'select'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/brief/BriefCrawler.kt:213:22 Method 'iterator()' is ambiguous for this expression. Applicable candidates:
fun <T> Enumeration<T>.iterator(): Iterator<T>
fun <T> Iterator<T>.iterator(): Iterator<T>
fun <K, V> Map<out K, V>.iterator(): Iterator<Map.Entry<K, V>>
fun <K, V> MutableMap<K, V>.iterator(): MutableIterator<MutableMap.MutableEntry<K, V>>
fun CharSequence.iterator(): CharIterator
fun BufferedInputStream.iterator(): ByteIterator
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/tools/media/MediaControlTool.kt:115:17 Unresolved reference 'AutomationBridge'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/tools/media/MediaControlTool.kt:119:38 Unresolved reference 'AutomationBridge'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/tools/media/MediaControlTool.kt:134:21 Unresolved reference 'AutomationBridge'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/tools/media/MediaControlTool.kt:137:25 Unresolved reference 'AutomationBridge'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/FridayService.kt:389:37 Unresolved reference 'TextToSpeak'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/overlay/FridayOverlayContent.kt:271:21 Unresolved reference 'withStyle'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/DailyBriefScreen.kt:251:44 Unresolved reference 'BorderStroke'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/DailyBriefScreen.kt:297:30 Unresolved reference 'BorderStroke'.
e: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/DailyBriefScreen.kt:432:22 Unresolved reference 'verticalScroll'.
> Task :app:compileDebugKotlin FAILED
FAILURE: Build failed with an exception.
25 actionable tasks: 25 executed
* What went wrong:
Execution failed for task ':app:compileDebugKotlin'.
> A failure occurred while executing org.jetbrains.kotlin.compilerRunner.GradleCompilerRunnerWithWorkers$GradleKotlinCompilerWorkAction
   > Compilation error. See log for more details
* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to generate a Build Scan (Powered by Develocity).
> Get more help at https://help.gradle.org.
BUILD FAILED in 3m 57s