Run ./gradlew assembleDebug
Welcome to Gradle 9.1.0!
Here are the highlights of this release:
 - Full Java 25 support
 - Native task graph visualization
 - Enhanced console output
For more details see https://docs.gradle.org/9.1.0/release-notes.html
Starting a Gradle Daemon (subsequent builds will be faster)
Calculating task graph as no cached configuration is available for tasks: assembleDebug
> Configure project :app
WARNING: The option setting 'android.disallowKotlinSourceSets=false' is experimental.
The current default is 'true'.
> Task :app:generateDebugAssets UP-TO-DATE
> Task :app:downloadWhisperModel
Whisper model already exists, skipping download.
> Task :app:preBuild
> Task :app:preDebugBuild
> Task :app:mergeDebugNativeDebugMetadata NO-SOURCE
> Task :app:javaPreCompileDebug FROM-CACHE
> Task :app:desugarDebugFileDependencies FROM-CACHE
> Task :app:mergeDebugAssets
> Task :app:parseDebugLocalResources
> Task :app:generateDebugRFile
> Task :app:processDebugMainManifest
> Task :app:processDebugManifest
> Task :app:processDebugManifestForPackage
> Task :app:mergeDebugResources
> Task :app:checkDebugDuplicateClasses
> Task :app:processDebugResources
> Task :app:mergeExtDexDebug FROM-CACHE
> Task :app:preReleaseBuild
> Task :app:mergeLibDexDebug FROM-CACHE
> Task :app:compressDebugAssets
> Task :app:mergeDebugJniLibFolders
> Task :app:validateSigningDebug
> Task :app:writeDebugAppMetadata
> Task :app:writeDebugSigningConfigVersions
> Task :app:configureCMakeRelease[arm64-v8a]
Checking the license for package CMake 3.22.1 in /usr/local/lib/android/sdk/licenses
License for package CMake 3.22.1 accepted.
Preparing "Install CMake 3.22.1 v.3.22.1".
"Install CMake 3.22.1 v.3.22.1" ready.
Installing CMake 3.22.1 in /usr/local/lib/android/sdk/cmake/3.22.1
"Install CMake 3.22.1 v.3.22.1" complete.
"Install CMake 3.22.1 v.3.22.1" finished.
> Task :app:kspDebugKotlin
> Task :app:configureCMakeRelease[x86_64]
> Task :app:compileDebugKotlin
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/core/db/FridayDatabase.kt:28:18 'fun fallbackToDestructiveMigration(): RoomDatabase.Builder<FridayDatabase>' is deprecated. Replace by overloaded version with parameter to indicate if all tables should be dropped or not.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/intelligence/nlu/SemanticIntentRouter.kt:265:51 Unchecked cast of 'Any!' to 'Array<Array<FloatArray>>'.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/tools/system/SystemControlsTool.kt:188:49 'static fun getDefaultAdapter(): BluetoothAdapter!' is deprecated. Deprecated in Java.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:495:25 'fun LinearProgressIndicator(progress: Float, modifier: Modifier = ..., color: Color = ..., trackColor: Color = ..., strokeCap: StrokeCap = ...): Unit' is deprecated. Use the overload that takes `progress` as a lambda.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:524:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:536:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:550:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:575:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:589:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:604:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:626:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:633:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:640:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:647:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:654:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:756:29 'fun LinearProgressIndicator(progress: Float, modifier: Modifier = ..., color: Color = ..., trackColor: Color = ..., strokeCap: StrokeCap = ...): Unit' is deprecated. Use the overload that takes `progress` as a lambda.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:841:29 'fun LinearProgressIndicator(progress: Float, modifier: Modifier = ..., color: Color = ..., trackColor: Color = ..., strokeCap: StrokeCap = ...): Unit' is deprecated. Use the overload that takes `progress` as a lambda.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:890:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:898:17 'fun Divider(modifier: Modifier = ..., thickness: Dp = ..., color: Color = ...): Unit' is deprecated. Renamed to HorizontalDivider.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:1427:53 'val Icons.Filled.Chat: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.Chat.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:1572:49 'val Icons.Filled.Chat: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.Chat.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/MainActivity.kt:1771:57 'val Icons.Filled.Send: ImageVector' is deprecated. Use the AutoMirrored version at Icons.AutoMirrored.Filled.Send.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/SetupWizard.kt:75:56 'fun EnterTransition.with(exit: ExitTransition): ContentTransform' is deprecated. Infix fun EnterTransition.with(ExitTransition) has been renamed to togetherWith.
w: file:///home/runner/work/Friday/Friday/app/src/main/java/com/friday/assistant/ui/screens/SetupWizard.kt:77:57 'fun EnterTransition.with(exit: ExitTransition): ContentTransform' is deprecated. Infix fun EnterTransition.with(ExitTransition) has been renamed to togetherWith.
> Task :app:compileDebugJavaWithJavac NO-SOURCE
> Task :app:processDebugJavaRes
> Task :app:configureCMakeRelease[arm64-v8a]
C/C++: CMAKE_BUILD_TYPE=Release
> Task :app:configureCMakeRelease[x86_64]
C/C++: CMAKE_BUILD_TYPE=Release
> Task :app:dexBuilderDebug
> Task :app:mergeDebugGlobalSynthetics
> Task :app:mergeDebugJavaResource
> Task :app:mergeProjectDexDebug
> Task :app:buildCMakeRelease[x86_64]
C/C++: ninja: Entering directory `/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64'
C/C++: /usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++ --target=x86_64-none-linux-android24 --sysroot=/usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot -DGGML_BACKEND_SHARED -DGGML_SHARED -DGGML_USE_CPU -DLLAMA_SHARED -Dfriday_native_EXPORTS -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/ggml/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src/src/. -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src/src/../include -I/hom
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:138:26: warning: 'llama_load_model_from_file' is deprecated: use llama_model_load_from_file instead [-Wdeprecated-declarations]
C/C++:   138 |     llama_model *model = llama_load_model_from_file(path, mparams);
C/C++:       |                          ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:481:5: note: 'llama_load_model_from_file' has been explicitly marked deprecated here
C/C++:   481 |     DEPRECATED(LLAMA_API struct llama_model * llama_load_model_from_file(
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:156:13: error: no member named 'flash_attn' in 'llama_context_params'
C/C++:   156 |     cparams.flash_attn = true; // Flash Attention: major CPU attention speedup
C/C++:       |     ~~~~~~~ ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:158:26: warning: 'llama_new_context_with_model' is deprecated: use llama_init_from_model instead [-Wdeprecated-declarations]
C/C++:   158 |     llama_context *ctx = llama_new_context_with_model(model, cparams);
C/C++:       |                          ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:518:5: note: 'llama_new_context_with_model' has been explicitly marked deprecated here
C/C++:   518 |     DEPRECATED(LLAMA_API struct llama_context * llama_new_context_with_model(
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:161:9: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
C/C++:   161 |         llama_free_model(model);
C/C++:       |         ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
C/C++:   509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:182:13: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
C/C++:   182 |             llama_free_model(state->model);
C/C++:       |             ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
C/C++:   509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: 4 warnings and 1 error generated.
> Task :app:buildCMakeRelease[arm64-v8a] FAILED
C/C++: ninja: Entering directory `/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a'
C/C++: /usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++ --target=aarch64-none-linux-android24 --sysroot=/usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot -DGGML_BACKEND_SHARED -DGGML_SHARED -DGGML_USE_CPU -DLLAMA_SHARED -Dfriday_native_EXPORTS -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/ggml/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src/src/. -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src
FAILURE: Build completed with 2 failures.
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:138:26: warning: 'llama_load_model_from_file' is deprecated: use llama_model_load_from_file instead [-Wdeprecated-declarations]
C/C++:   138 |     llama_model *model = llama_load_model_from_file(path, mparams);
C/C++:       |                          ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:481:5: note: 'llama_load_model_from_file' has been explicitly marked deprecated here
C/C++:   481 |     DEPRECATED(LLAMA_API struct llama_model * llama_load_model_from_file(
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:156:13: error: no member named 'flash_attn' in 'llama_context_params'
C/C++:   156 |     cparams.flash_attn = true; // Flash Attention: major CPU attention speedup
C/C++:       |     ~~~~~~~ ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:158:26: warning: 'llama_new_context_with_model' is deprecated: use llama_init_from_model instead [-Wdeprecated-declarations]
C/C++:   158 |     llama_context *ctx = llama_new_context_with_model(model, cparams);
C/C++:       |                          ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:518:5: note: 'llama_new_context_with_model' has been explicitly marked deprecated here
C/C++:   518 |     DEPRECATED(LLAMA_API struct llama_context * llama_new_context_with_model(
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:161:9: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
C/C++:   161 |         llama_free_model(model);
C/C++:       |         ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
C/C++:   509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:182:13: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
C/C++:   182 |             llama_free_model(state->model);
C/C++:       |             ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
C/C++:   509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
C/C++:       |     ^
C/C++: /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
C/C++:    30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
C/C++:       |                                                        ^
C/C++: 4 warnings and 1 error generated.
> Task :app:buildCMakeRelease[x86_64] FAILED
38 actionable tasks: 32 executed, 6 from cache
1: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':app:buildCMakeRelease[x86_64]'.
> com.android.ide.common.process.ProcessException: ninja: Entering directory `/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64'
  [1/197] Building C object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-alloc.c.o
  [2/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml.cpp.o
  [3/197] Building CXX object CMakeFiles/friday_native.dir/friday_jni.cpp.o
  FAILED: CMakeFiles/friday_native.dir/friday_jni.cpp.o 
  /usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++ --target=x86_64-none-linux-android24 --sysroot=/usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot -DGGML_BACKEND_SHARED -DGGML_SHARED -DGGML_USE_CPU -DLLAMA_SHARED -Dfriday_native_EXPORTS -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/ggml/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src/src/. -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/whispercpp_new-src/src/../include -I/home/run
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:138:26: warning: 'llama_load_model_from_file' is deprecated: use llama_model_load_from_file instead [-Wdeprecated-declarations]
    138 |     llama_model *model = llama_load_model_from_file(path, mparams);
        |                          ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:481:5: note: 'llama_load_model_from_file' has been explicitly marked deprecated here
    481 |     DEPRECATED(LLAMA_API struct llama_model * llama_load_model_from_file(
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:156:13: error: no member named 'flash_attn' in 'llama_context_params'
    156 |     cparams.flash_attn = true; // Flash Attention: major CPU attention speedup
        |     ~~~~~~~ ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:158:26: warning: 'llama_new_context_with_model' is deprecated: use llama_init_from_model instead [-Wdeprecated-declarations]
    158 |     llama_context *ctx = llama_new_context_with_model(model, cparams);
        |                          ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:518:5: note: 'llama_new_context_with_model' has been explicitly marked deprecated here
    518 |     DEPRECATED(LLAMA_API struct llama_context * llama_new_context_with_model(
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:161:9: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
    161 |         llama_free_model(model);
        |         ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
    509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:182:13: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
    182 |             llama_free_model(state->model);
        |             ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
    509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  4 warnings and 1 error generated.
  [4/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-threading.cpp.o
  [5/197] Building C object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml.c.o
  [6/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-backend.cpp.o
  [7/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-opt.cpp.o
  [8/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-backend-meta.cpp.o
  ninja: build stopped: subcommand failed.
  
  C++ build system [build] failed while executing:
      /usr/local/lib/android/sdk/cmake/3.22.1/bin/ninja \
        -C \
        /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/x86_64 \
        friday_native \
        ggml \
        ggml-base \
        ggml-cpu \
        llama \
        parakeet \
        whisper
    from /home/runner/work/Friday/Friday/app
* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to generate a Build Scan (Powered by Develocity).
> Get more help at https://help.gradle.org.
==============================================================================
2: Task failed with an exception.
-----------
* What went wrong:
Execution failed for task ':app:buildCMakeRelease[arm64-v8a]'.
> com.android.ide.common.process.ProcessException: ninja: Entering directory `/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a'
  [1/197] Building C object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-alloc.c.o
  [2/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml.cpp.o
  [3/197] Building CXX object CMakeFiles/friday_native.dir/friday_jni.cpp.o
  FAILED: CMakeFiles/friday_native.dir/friday_jni.cpp.o 
  /usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/bin/clang++ --target=aarch64-none-linux-android24 --sysroot=/usr/local/lib/android/sdk/ndk/28.2.13676358/toolchains/llvm/prebuilt/linux-x86_64/sysroot -DGGML_BACKEND_SHARED -DGGML_SHARED -DGGML_USE_CPU -DLLAMA_SHARED -Dfriday_native_EXPORTS -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/ggml/include -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src/src/. -I/home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/whispercpp_new-src/src/
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:138:26: warning: 'llama_load_model_from_file' is deprecated: use llama_model_load_from_file instead [-Wdeprecated-declarations]
    138 |     llama_model *model = llama_load_model_from_file(path, mparams);
        |                          ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:481:5: note: 'llama_load_model_from_file' has been explicitly marked deprecated here
    481 |     DEPRECATED(LLAMA_API struct llama_model * llama_load_model_from_file(
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:156:13: error: no member named 'flash_attn' in 'llama_context_params'
    156 |     cparams.flash_attn = true; // Flash Attention: major CPU attention speedup
        |     ~~~~~~~ ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:158:26: warning: 'llama_new_context_with_model' is deprecated: use llama_init_from_model instead [-Wdeprecated-declarations]
    158 |     llama_context *ctx = llama_new_context_with_model(model, cparams);
        |                          ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:518:5: note: 'llama_new_context_with_model' has been explicitly marked deprecated here
    518 |     DEPRECATED(LLAMA_API struct llama_context * llama_new_context_with_model(
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:161:9: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
    161 |         llama_free_model(model);
        |         ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
    509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  /home/runner/work/Friday/Friday/app/src/main/cpp/friday_jni.cpp:182:13: warning: 'llama_free_model' is deprecated: use llama_model_free instead [-Wdeprecated-declarations]
    182 |             llama_free_model(state->model);
        |             ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:509:5: note: 'llama_free_model' has been explicitly marked deprecated here
    509 |     DEPRECATED(LLAMA_API void llama_free_model(struct llama_model * model),
        |     ^
  /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a/_deps/llamacpp_new-src/include/llama.h:30:56: note: expanded from macro 'DEPRECATED'
     30 | #    define DEPRECATED(func, hint) func __attribute__((deprecated(hint)))
        |                                                        ^
  4 warnings and 1 error generated.
  [4/197] Building C object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml.c.o
  [5/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-threading.cpp.o
  [6/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-backend.cpp.o
  [7/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-opt.cpp.o
  [8/197] Building CXX object _deps/llamacpp_new-build/ggml/src/CMakeFiles/ggml-base.dir/ggml-backend-meta.cpp.o
  ninja: build stopped: subcommand failed.
  
  C++ build system [build] failed while executing:
      /usr/local/lib/android/sdk/cmake/3.22.1/bin/ninja \
        -C \
        /home/runner/work/Friday/Friday/app/.cxx/Release/4g4935v6/arm64-v8a \
        friday_native \
        ggml \
        ggml-base \
        ggml-cpu \
        llama \
        parakeet \
        whisper
    from /home/runner/work/Friday/Friday/app
* Try:
> Run with --stacktrace option to get the stack trace.
> Run with --info or --debug option to get more log output.
> Run with --scan to generate a Build Scan (Powered by Develocity).
> Get more help at https://help.gradle.org.
==============================================================================
BUILD FAILED in 1m 42s
Configuration cache entry stored.
Error: Process completed with exit code 1.